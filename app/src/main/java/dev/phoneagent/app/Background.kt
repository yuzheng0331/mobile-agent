package dev.phoneagent.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.phoneagent.app.shell.Shell

/**
 * Where background runs go, best first (docs/virtual-display-experiments.md):
 * - role mode: we hold [ROLE], which grants the display permissions below, so we make the display and start apps
 *   on it ourselves. Survives reboots; an app update or reinstall takes the role away.
 * - shell mode: the shell server ([Shell]) makes the display and starts apps on it.
 * - neither: the run goes on the main display (the caller decides).
 */
object Background {
    const val ROLE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"
    private val PERMISSIONS = listOf("android.permission.ADD_TRUSTED_DISPLAY", "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY")

    // The shell server's set minus DEVICE_DISPLAY_GROUP, as tested with the role on Android 14. Most of these
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* are hidden and an app may not read them by reflection: PUBLIC 0x1,
    // OWN_CONTENT_ONLY 0x8, SUPPORTS_TOUCH 0x40, ROTATES_WITH_CONTENT 0x80, DESTROY_CONTENT_ON_REMOVAL 0x100,
    // TRUSTED 0x400, OWN_DISPLAY_GROUP 0x800, ALWAYS_UNLOCKED 0x1000, TOUCH_FEEDBACK_DISABLED 0x2000, OWN_FOCUS 0x4000,
    // STEAL_TOP_FOCUS_DISABLED 0x10000. Without PUBLIC accessibility sees no windows on it.
    private const val FLAGS = 0x17dc9

    // One per run (see release).
    @Volatile private var own: VirtualDisplay? = null
    private var reader: ImageReader? = null

    fun hasRole(context: Context) = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** The user's "后台运行" choice. */
    fun wanted(context: Context) = prefs(context).getBoolean(WANTED, false)

    fun want(context: Context, on: Boolean) = prefs(context).edit().putBoolean(WANTED, on).apply()

    /** Held the role once and lost it since: an update or reinstall took it, and the user should hear so. */
    fun lostRole(context: Context) = prefs(context).getBoolean(HAD_ROLE, false) && !hasRole(context)

    /** Gives the role back through the shell server; false without one or when the system refuses. */
    fun restoreRole(context: Context): Boolean {
        val server = Shell.server ?: return false
        val granted = runCatching { server.grantRole() }.onFailure { Log.i("PhoneAgent", "后台模式：分配角色出错：$it") }.getOrDefault(false)
        val done = granted && hasRole(context)
        Log.i("PhoneAgent", "后台模式：分配角色${if (done) "成功" else if (granted) "后仍没有权限" else "失败（见 shell 服务日志）"}")
        if (done) prefs(context).edit().putBoolean(HAD_ROLE, true).apply()
        return done
    }

    private fun prefs(context: Context) = context.getSharedPreferences("background", Context.MODE_PRIVATE)
    private const val WANTED = "wanted"
    private const val HAD_ROLE = "hadRole"

    /** Whether [displayId] is ours (role mode): then we start apps on it, and back has no shell to go through. */
    fun owns(displayId: Int) = displayId != 0 && own?.display?.displayId == displayId

    /** The background display, made the given size if there is none yet; null when neither mode is available. */
    @Synchronized
    fun display(context: Context, width: Int, height: Int, densityDpi: Int): Int? {
        if (hasRole(context)) {
            prefs(context).edit().putBoolean(HAD_ROLE, true).apply()
            ownDisplay(context, width, height, densityDpi)?.let { return it }
        }
        return Shell.display(width, height, densityDpi)
    }

    /**
     * Ends a role mode run: the display goes and the apps' pages on it with it (DESTROY_CONTENT_ON_REMOVAL), so the
     * next run starts them at their start screen. Role mode has no back key to get there (the global back would
     * hit the user's app), and a CLEAR_TASK relaunch left WeChat's window black and invisible to accessibility.
     */
    @Synchronized
    fun release() {
        own?.release()
        reader?.close()
        own = null
        reader = null
    }

    private fun ownDisplay(context: Context, width: Int, height: Int, densityDpi: Int): Int? {
        own?.let { return it.display.displayId }
        // Our own surface, drained as frames come: screenshots go through accessibility.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val display = runCatching {
            context.getSystemService(DisplayManager::class.java).createVirtualDisplay("phone-agent", width, height, densityDpi, reader.surface, FLAGS)
        }.onFailure { Log.i("PhoneAgent", "后台模式：自建虚拟屏失败：$it") }.getOrNull()
        if (display == null) {
            reader.close()
            return null
        }
        own = display
        this.reader = reader
        return display.display.displayId
    }
}
