package dev.phoneagent.app.shell

import android.annotation.TargetApi
import android.content.AttributionSource
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplayConfig
import android.media.ImageReader
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import dev.phoneagent.app.Background
import java.lang.reflect.Proxy
import kotlin.system.exitProcess

/**
 * The background mode's shell server: runs as the shell user and does the few things an app may not,
 * that is create a virtual display, start apps on it and send it a key (docs/virtual-display-experiments.md).
 * app_process starts it from our APK: over adb for now (tools/probe/shell_start.sh), later from Shizuku or
 * our own wireless-debugging starter. It hands its binder to the app through [ShellProvider], serves only
 * that app's uid, and exits when the app dies, which removes its displays and closes the apps on them.
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE) // the display flags the experiment needs are Android 14's
object ShellServer {
    @JvmStatic
    fun main(args: Array<String>) {
        Looper.prepareMainLooper()
        val server = Server()
        val reply = attach(server)
        server.appUid = reply.getInt(ShellProvider.UID)
        reply.getBinder(ShellProvider.APP)!!.linkToDeath({ exitProcess(0) }, 0)
        println("Phone Agent shell server attached to uid ${server.appUid}")
        Looper.loop()
    }

    /** Calls our ShellProvider as the shell user, the way Shizuku hands out its binder. */
    private fun attach(server: IBinder): Bundle {
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
        val token = Binder()
        val holder = activityManager.javaClass
            .getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
            .invoke(activityManager, ShellProvider.AUTHORITY, 0, token, null) ?: error("Phone Agent is not installed")
        try {
            val provider = holder.javaClass.getField("provider").get(holder)!!
            val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
            return provider.javaClass
                .getMethod("call", AttributionSource::class.java, String::class.java, String::class.java, String::class.java, Bundle::class.java)
                .invoke(provider, source, ShellProvider.AUTHORITY, ShellProvider.ATTACH, null, Bundle().apply { putBinder(ShellProvider.SHELL, server) }) as Bundle?
                ?: error("Phone Agent refused the shell server")
        } finally {
            activityManager.javaClass.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
                .invoke(activityManager, ShellProvider.AUTHORITY, token)
        }
    }

    private class Server : IShell.Stub() {
        @Volatile var appUid = -1
        private val main = Handler(Looper.getMainLooper())
        private val displays = HashMap<Int, Pair<ImageReader, Any>>()

        private val displayManager: Any by lazy {
            val service = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, Context.DISPLAY_SERVICE) as IBinder
            Class.forName("android.hardware.display.IDisplayManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, service)!!
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (Binder.getCallingUid() != appUid) throw SecurityException("only Phone Agent may use its shell server")
            return super.onTransact(code, data, reply, flags)
        }

        @Synchronized
        override fun createDisplay(width: Int, height: Int, densityDpi: Int): Int {
            // Our own surface, drained as frames come: nobody reads it, the app takes screenshots through accessibility.
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, main)
            val config = VirtualDisplayConfig.Builder("phone-agent", width, height, densityDpi)
                .setFlags(FLAGS.fold(0) { all, name -> all or DisplayManager::class.java.getField("VIRTUAL_DISPLAY_FLAG_$name").getInt(null) })
                .setSurface(reader.surface)
                .build()
            // The system keeps the display while this binder lives: with this process, at the latest.
            val token = Binder()
            val callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback")
            val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { _, method, args ->
                when {
                    method.declaringClass == Any::class.java -> method.invoke(token, *args.orEmpty())
                    method.name == "asBinder" -> token
                    else -> null
                }
            }
            val id = displayManager.javaClass.methods.first { it.name == "createVirtualDisplay" && it.parameterCount == 4 }
                .invoke(displayManager, config, callback, null, SHELL_PACKAGE) as Int
            displays[id] = reader to callback
            return id
        }

        @Synchronized
        override fun releaseDisplay(displayId: Int) {
            val (reader, callback) = displays.remove(displayId) ?: return
            displayManager.javaClass.methods.first { it.name == "releaseVirtualDisplay" }.invoke(displayManager, callback)
            reader.close()
        }

        override fun start(component: String, displayId: Int): Boolean =
            COMPONENT.matches(component) && ours(displayId) && run("am", "start", "--display", "$displayId", "-n", component)

        override fun key(displayId: Int, keyCode: Int): Boolean =
            ours(displayId) && run("input", "-d", "$displayId", "keyevent", "$keyCode")

        // The role is meant for companion device apps: the qualification check is lifted for this one call only.
        override fun grantRole(): Boolean {
            if (!run("cmd", "role", "set-bypassing-role-qualification", "true")) return false
            try {
                return run("cmd", "role", "add-role-holder", Background.ROLE, PACKAGE)
            } finally {
                run("cmd", "role", "set-bypassing-role-qualification", "false")
            }
        }

        // Only on displays this server made: never the user's main display.
        @Synchronized
        private fun ours(displayId: Int) = displayId in displays

        private fun run(vararg command: String): Boolean {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val ok = process.waitFor() == 0 && "Error" !in output
            // To the server's own log (shell_start.sh); these commands print no screen content.
            if (!ok) println("failed (${process.exitValue()}): ${command.joinToString(" ")}\n$output")
            return ok
        }
    }

    private const val SHELL_PACKAGE = "com.android.shell"
    private const val PACKAGE = "dev.phoneagent.app"
    private val COMPONENT = Regex("[\\w.]+/[\\w.$]+")

    // What docs/virtual-display-experiments.md settled on: scrcpy's --new-display set, plus STEAL_TOP_FOCUS_DISABLED
    // so typing on the main display keeps working; ALWAYS_UNLOCKED lets runs go on while the phone is locked.
    private val FLAGS = listOf(
        "PUBLIC", "OWN_CONTENT_ONLY", "SUPPORTS_TOUCH", "ROTATES_WITH_CONTENT", "DESTROY_CONTENT_ON_REMOVAL", "TRUSTED",
        "OWN_DISPLAY_GROUP", "ALWAYS_UNLOCKED", "TOUCH_FEEDBACK_DISABLED", "OWN_FOCUS", "DEVICE_DISPLAY_GROUP", "STEAL_TOP_FOCUS_DISABLED",
    )
}
