package dev.phoneagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Debug only, for the cores experiment: a foreground service held while the bench measures. */
class BenchService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("bench", "测速", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "bench").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("测速中").build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
