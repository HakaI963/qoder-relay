package com.qoder.relay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class RelayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as RelayApp
        createChannel()
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Qoder Relay")
            .setContentText("OpenAI-compatible endpoint on :${app.port}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, notif,
            if (Build.VERSION.SDK_INT >= 34) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (app.server == null) app.startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        (application as RelayApp).server?.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Relay", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTIF_ID = 42
        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, RelayService::class.java))
    }
}
