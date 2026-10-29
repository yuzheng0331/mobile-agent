package dev.phoneagent.app.shell

import android.os.Binder
import android.os.IBinder
import android.util.Log

/** The app's side of [ShellServer]: there once a server has attached, gone again when it dies. */
object Shell {
    /** The server watches this and exits with our process. */
    val token = Binder()

    @Volatile var server: IShell? = null
        private set

    // Made on first use and kept while the server lives, so the apps on it stay warm between runs.
    @Volatile private var display = NONE

    fun attach(binder: IBinder) {
        binder.linkToDeath({
            Log.i("PhoneAgent", "后台模式：shell 服务已退出")
            server = null
            display = NONE
        }, 0)
        display = NONE
        server = IShell.Stub.asInterface(binder)
    }

    /** The background display, made the given size if there is none yet; null without a server or when it fails. */
    @Synchronized
    fun display(width: Int, height: Int, densityDpi: Int): Int? {
        val shell = server ?: return null
        if (display == NONE) display = runCatching { shell.createDisplay(width, height, densityDpi) }
            .onFailure { Log.i("PhoneAgent", "后台模式：建虚拟屏失败：$it") }.getOrDefault(NONE)
        return display.takeIf { it != NONE }
    }

    private const val NONE = -1
}
