package com.qoder.relay

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that owns the relay. This is what keeps it alive with the screen off:
 *  - partial WakeLock  → CPU keeps serving while the screen is off / in Doze
 *  - high-perf WifiLock → Wi-Fi radio is not put to sleep (the classic "LAN server stops answering")
 *  - specialUse FGS type → no 6-hour dataSync limit on Android 15
 *  - START_STICKY + watchdog alarm → restarted if the system or a task killer stops it
 *  - 15 s health check → restarts the HTTP server / signer if they died
 */
class RelayService : Service() {

    private val app get() = application as RelayApp
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { handler.post { updateNotification() } }

    private val health = object : Runnable {
        override fun run() {
            if (app.wantRunning) {
                if (!app.isRunning()) app.ensureServer()
                acquireLocks()
                updateNotification()
            }
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        synchronized(app.listeners) { app.listeners.add(onChange) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.wantRunning = false
            shutdown()
            return START_NOT_STICKY
        }
        app.wantRunning = true
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from background
            app.ensureServer()
            stopSelf()
            return START_NOT_STICKY
        }
        app.ensureServer()
        acquireLocks()
        handler.removeCallbacks(health)
        handler.postDelayed(health, 15_000)
        scheduleWatchdog(this)
        return START_STICKY
    }

    /** Swiping the app from recents must NOT stop the relay. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (app.wantRunning) scheduleWatchdog(this, 2_000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        synchronized(app.listeners) { app.listeners.remove(onChange) }
        releaseLocks()
        // if we were killed rather than stopped by the user, come back
        if (app.wantRunning) scheduleWatchdog(this, 3_000) else app.stopServer()
        super.onDestroy()
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        cancelWatchdog(this)
        app.stopServer()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QoderRelay::server")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(mode, "QoderRelay::wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null; wifiLock = null
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RelayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val running = app.isRunning()
        val ip = app.lanAddress().ifEmpty { "127.0.0.1" }
        val accounts = app.repo.accounts.count { it.enabled }
        val text = if (running) "http://$ip:${app.port}/v1  ·  $accounts account${if (accounts == 1) "" else "s"}"
                   else "Starting… ${app.serverError}"
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(if (running) "Qoder Relay is running" else "Qoder Relay")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_relay)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private var lastText = ""
    private fun updateNotification() {
        val n = buildNotification()
        val t = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (t == lastText) return
        lastText = t
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n) }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Relay status", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) })
        }
    }

    /** Re-starts the service via an alarm if it is killed; also triggered on boot / app update. */
    class Restarter : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = ctx.applicationContext as RelayApp
            if (app.wantRunning) start(ctx)
        }
    }

    companion object {
        private const val CHANNEL = "relay"
        private const val NOTIF_ID = 42
        const val ACTION_STOP = "com.qoder.relay.STOP"

        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, RelayService::class.java)) }
            catch (_: Exception) { (ctx.applicationContext as RelayApp).ensureServer() }
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, RelayService::class.java).setAction(ACTION_STOP))
        }

        private fun watchdogIntent(ctx: Context) = PendingIntent.getBroadcast(ctx, 7,
            Intent(ctx, Restarter::class.java).setAction("com.qoder.relay.WATCHDOG"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** Repeating inexact alarm (no exact-alarm permission needed) that revives the service. */
        fun scheduleWatchdog(ctx: Context, firstInMs: Long = 15 * 60_000L) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            runCatching {
                am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + firstInMs, 15 * 60_000L, watchdogIntent(ctx))
            }
        }

        fun cancelWatchdog(ctx: Context) {
            runCatching { ctx.getSystemService(AlarmManager::class.java)?.cancel(watchdogIntent(ctx)) }
        }
    }
}
