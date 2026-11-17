package app.switchboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service held while a reply or tool run is in progress, so Android keeps the app's network
 * and CPU alive when it's in the background (e.g. while the user watches Termux or locks the screen).
 */
class WorkService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    /** Keeps Wi-Fi at full speed with the screen off (big model downloads). */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Working in the background", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Switchboard is working")
            .setContentText(intent?.getStringExtra(EXTRA_TEXT) ?: "Waiting for the model…")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "switchboard:work").apply { setReferenceCounted(false); acquire(6 * 3_600_000L) }
        if (wifiLock == null) wifiLock = runCatching {
            @Suppress("DEPRECATION")
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "switchboard:work").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        return START_NOT_STICKY
    }

    override fun onDestroy() { wakeLock?.release(); wakeLock = null; wifiLock?.release(); wifiLock = null; super.onDestroy() }

    companion object {
        private const val CHANNEL = "work"
        private const val ID = 1
        private const val EXTRA_TEXT = "text"

        /** What's keeping the service running (a reply, a model download, ...), by key; it stops when nothing is left. */
        private val holds = java.util.concurrent.ConcurrentHashMap<String, String>()

        private fun show(ctx: Context, text: String) = runCatching {
            val i = Intent(ctx, WorkService::class.java).putExtra(EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        } // best effort: the work still runs if Android refuses the service

        /** Keeps the app running in the background for [key], showing [text] (call again to update it). */
        fun hold(ctx: Context, key: String, text: String) { holds[key] = text; show(ctx, holdsText()) }

        fun release(ctx: Context, key: String) {
            if (holds.remove(key) == null) return
            if (holds.isEmpty()) ctx.stopService(Intent(ctx, WorkService::class.java)) else show(ctx, holdsText())
        }

        private fun holdsText() = holds.values.joinToString(" · ").take(200)

        fun start(ctx: Context, text: String? = null) = hold(ctx, "main", text ?: "Waiting for the model…")
        fun stop(ctx: Context) = release(ctx, "main")

        /** A normal (dismissable) notification, e.g. "model downloaded". */
        fun announce(ctx: Context, text: String) = runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val n = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(ctx, DONE_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(ctx))
                .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("Switchboard").setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).build()
            nm.notify(text.hashCode(), n)
        }
        private const val DONE_CHANNEL = "done"
    }
}
