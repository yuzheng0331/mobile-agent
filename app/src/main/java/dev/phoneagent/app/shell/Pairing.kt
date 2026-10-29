package dev.phoneagent.app.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pairing with wireless debugging, once: the code goes in through a notification, because the pairing dialog
 * in the settings closes as soon as another app comes to the front. Pairs, then starts the shell server.
 */
object Pairing {
    private const val CHANNEL = "pairing"
    private const val ID = 1
    private const val CODE = "code"

    fun show(context: Context, text: String = "在“无线调试”里点“使用配对码配对设备”，然后在这里填 6 位配对码") {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台运行授权", NotificationManager.IMPORTANCE_HIGH))
        // Mutable: the system writes the typed code into it.
        val reply = PendingIntent.getBroadcast(
            context, 0, Intent(context, PairingReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(CODE).setLabel("配对码").build()
        val action = Notification.Action.Builder(null, "输入配对码", reply).addRemoteInput(input).build()
        manager.notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Phone Agent：配对无线调试")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(action)
            .build())
    }

    fun done(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Phone Agent：配对无线调试")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build())
    }

    internal fun code(intent: Intent) = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(CODE)?.toString()?.trim()
}

/** Not exported: only our own notification's reply reaches it. */
class PairingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = Pairing.code(intent) ?: return
        val app = context.applicationContext
        Pairing.show(app, "正在配对……")
        // Our process lives on with the accessibility service, so a plain thread is enough.
        Thread {
            if (!AdbLauncher.pair(app, code)) return@Thread Pairing.show(app, "${AdbLauncher.problem}。可以再填一次")
            Pairing.done(app, if (AdbLauncher.start(app)) "已配对，后台运行已可用" else "已配对，但启动没成功：${AdbLauncher.problem}")
        }.start()
    }
}
