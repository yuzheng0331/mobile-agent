package dev.phoneagent.app.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/**
 * Where [ShellServer] hands the app its binder. Only the shell user (or root) may: any other caller could
 * slip in a binder of its own, so it gets nothing.
 */
class ShellProvider : ContentProvider() {
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != ATTACH || Binder.getCallingUid() !in listOf(Process.SHELL_UID, Process.ROOT_UID)) return null
        Shell.attach(extras?.getBinder(SHELL) ?: return null)
        return Bundle().apply {
            putBinder(APP, Shell.token)
            putInt(UID, Process.myUid())
        }
    }

    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0

    companion object {
        const val AUTHORITY = "dev.phoneagent.app.shell"
        const val ATTACH = "attach"
        const val SHELL = "shell"
        const val APP = "app"
        const val UID = "uid"
    }
}
