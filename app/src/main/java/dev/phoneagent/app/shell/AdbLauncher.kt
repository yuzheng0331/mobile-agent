package dev.phoneagent.app.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.provider.Settings
import android.os.SystemClock
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Starts [ShellServer] through this phone's own wireless debugging, the way Shizuku does, so the user needs
 * no computer (docs/research/next-phase-design-review.md §6). Pair once ([pair]); after that [start] turns
 * wireless debugging on if it is off (we hold WRITE_SECURE_SETTINGS from the first connection), connects
 * to adbd on 127.0.0.1, starts the server and puts wireless debugging back as it was. Needs Wi-Fi: Android
 * won't run wireless debugging without it, though the server stays up once started.
 *
 * Only ever this phone's adbd: services found on the network are used only when their address is ours.
 * Blocking; call from a worker thread.
 */
object AdbLauncher {
    private const val PAIRING = "_adb-tls-pairing._tcp"
    private const val CONNECT = "_adb-tls-connect._tcp"
    private const val ADB_WIFI = "adb_wifi_enabled"
    private const val FIND_MS = 8_000L
    private const val ATTACH_MS = 5_000L
    private const val MORE_MS = 1_000L

    /** Why the last [start] or [pair] failed, for the user; null after a success. */
    @Volatile var problem: String? = null
        private set

    /** Pairs with the code wireless debugging shows ("使用配对码配对设备"); the pairing dialog must be open. */
    @Synchronized
    fun pair(context: Context, code: String): Boolean = attempt {
        identity(context)
        val ports = find(context, PAIRING)
        if (ports.isEmpty()) return@attempt fail("没找到配对端口：请在“无线调试”里点“使用配对码配对设备”，保持那个窗口打开")
        var error: Throwable? = null
        if (ports.none { port -> runCatching { runBlocking { Kadb.pair("127.0.0.1", port, code, "Phone Agent") } }.onFailure { error = it }.isSuccess }) {
            return@attempt fail("配对失败（配对码不对或已过期）：$error")
        }
        Log.i("PhoneAgent", "后台模式：无线调试已配对")
        true
    }

    /** Starts the shell server unless it is up already; false (see [problem]) when it can't. */
    @Synchronized
    fun start(context: Context): Boolean = attempt {
        if (Shell.server != null) return@attempt true
        if (!onWifi(context)) return@attempt fail("没有连 Wi-Fi：无线调试要连着 Wi-Fi 才能打开")
        val resolver = context.contentResolver
        val wasOn = Settings.Global.getInt(resolver, ADB_WIFI, 0) == 1
        if (!wasOn) {
            if (!canWriteSettings(context)) return@attempt fail("请先在开发者选项里打开“无线调试”，或者重新配对")
            Settings.Global.putInt(resolver, ADB_WIFI, 1)
        }
        try {
            identity(context)
            val ports = find(context, CONNECT)
            if (ports.isEmpty()) return@attempt fail("没找到无线调试的连接端口")
            // mDNS can still list a port adbd has left (seen: a stale one refused): take the first that answers.
            var error: Exception? = null
            val connected = ports.firstNotNullOfOrNull { port ->
                val adb = Kadb.create("127.0.0.1", port)
                try {
                    adb.shell("true")
                    adb
                } catch (e: Exception) {
                    error = e
                    adb.close()
                    null
                }
            } ?: return@attempt fail("连不上无线调试（还没配对？）：$error")
            connected.use { adb ->
                if (!canWriteSettings(context)) {
                    adb.shell("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
                }
                // As tools/probe/shell_start.sh: from the installed APK, left running after adb disconnects.
                val apk = context.applicationInfo.sourceDir
                adb.shell("CLASSPATH=$apk nohup app_process / ${ShellServer::class.java.name} > /data/local/tmp/phone-agent-shell.log 2>&1 &")
            }
            val deadline = SystemClock.uptimeMillis() + ATTACH_MS
            while (Shell.server == null && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
            if (Shell.server == null) return@attempt fail("shell 服务没有连上（见 /data/local/tmp/phone-agent-shell.log）")
            Log.i("PhoneAgent", "后台模式：shell 服务已由无线调试启动")
            true
        } catch (e: Exception) {
            // Typically not paired yet: adbd refuses our key.
            fail("连不上无线调试（还没配对？）：$e")
        } finally {
            // The server outlives wireless debugging, so leave it as the user had it.
            if (!wasOn) runCatching { Settings.Global.putInt(resolver, ADB_WIFI, 0) }
        }
    }

    private inline fun attempt(block: () -> Boolean): Boolean = block().also { if (it) problem = null }

    private fun fail(why: String): Boolean {
        problem = why
        Log.i("PhoneAgent", "后台模式：$why")
        return false
    }

    // Our adb key and certificate, kept in the app's private files (allowBackup is off): whoever holds them gets
    // a shell here. Kadb only keeps them in memory, so they are loaded on first use, or made and saved once.
    private var configured = false

    private fun identity(context: Context) {
        if (configured) return
        val dir = File(context.filesDir, "adb")
        val certFile = File(dir, "cert.pem")
        val keyFile = File(dir, "key.pem")
        if (certFile.isFile && keyFile.isFile) {
            KadbCert.set(certFile.readBytes(), keyFile.readBytes())
        } else {
            // Kadb's default is 120 days; adbd trusts the key, and an expired certificate would mean pairing again.
            val (cert, key) = KadbCert.get(cn = "Phone Agent", notAfter = System.currentTimeMillis() + CERT_DAYS * 86_400_000L)
            dir.mkdirs()
            keyFile.writeBytes(key)
            certFile.writeBytes(cert)
        }
        configured = true
    }

    private const val CERT_DAYS = 36_500L

    private fun canWriteSettings(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun onWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    /**
     * The ports this phone's adbd advertises for [type] over mDNS, in the order found: after the first, a short
     * while more for the others (mDNS may still list a port adbd has left). Empty if none shows up in time.
     */
    @Suppress("DEPRECATION") // resolveService: its replacement (registerServiceInfoCallback) is Android 14+ only
    private fun find(context: Context, type: String): List<Int> {
        val nsd = context.getSystemService(NsdManager::class.java)
        val ours = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.toSet()
        val ports = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val found = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host: InetAddress? = info.host
                        if (host != null && host in ours && ports.addIfAbsent(info.port)) found.countDown()
                    }
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
                })
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) { found.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            if (found.await(FIND_MS, TimeUnit.MILLISECONDS)) Thread.sleep(MORE_MS)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        return ports.toList()
    }
}
