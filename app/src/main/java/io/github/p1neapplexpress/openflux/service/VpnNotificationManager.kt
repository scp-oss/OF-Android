package io.github.p1neapplexpress.openflux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.ui.MainActivity
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.toUptimeHms
import java.util.Locale

class VpnNotificationManager(private val service: Service) {

    companion object {
        const val CHANNEL_ID = "io.github.p1neapplexpress.libp1npplydtransport.so.vpn"
        const val NOTIFICATION_ID = 1
        private const val UPDATE_INTERVAL_MS = 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val uid = Process.myUid()
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSampleAt = 0L
    private var connectStartAt = 0L

    // speedUpdater refreshes the pinned notification with the current
    // upload/download speed + connection uptime once a second while the
    // tunnel is running. TrafficStats is used instead of tapping the
    // packet path directly: the data plane here is a native tun2socks
    // process, opaque to this Kotlin code, but per-UID counters keep
    // working regardless of which process is actually moving bytes
    // through the TUN interface.
    private val speedUpdater = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = (now - lastSampleAt).coerceAtLeast(1)
            val rxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
            val txBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
            val rxPerSec = (rxBytes - lastRxBytes) * 1000 / elapsedMs
            val txPerSec = (txBytes - lastTxBytes) * 1000 / elapsedMs
            lastRxBytes = rxBytes
            lastTxBytes = txBytes
            lastSampleAt = now
            val uptimeSeconds = (now - connectStartAt) / 1000
            // Direct request: speed AND connection time in the notification's
            // own top line (the title), not split across title/text.
            val title = "↑ ${formatSpeed(txPerSec)}   ↓ ${formatSpeed(rxPerSec)}   ·   ${uptimeSeconds.toUptimeHms()}"
            updateContent(title, service.getString(R.string.notify_msg))
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun startForeground() {
        createChannel()
        service.startForeground(
            NOTIFICATION_ID,
            buildNotification(service.getString(R.string.notify_title), service.getString(R.string.notify_msg)),
        )
    }

    // startSpeedUpdates begins the live upload/download + uptime indicator;
    // call once the tunnel is actually passing traffic (tun2socks reported
    // running) — that moment is also this connection's own start time.
    fun startSpeedUpdates() {
        lastRxBytes = TrafficStats.getUidRxBytes(uid).coerceAtLeast(0)
        lastTxBytes = TrafficStats.getUidTxBytes(uid).coerceAtLeast(0)
        lastSampleAt = SystemClock.elapsedRealtime()
        connectStartAt = lastSampleAt
        handler.removeCallbacks(speedUpdater)
        handler.post(speedUpdater)
    }

    // stopSpeedUpdates cancels the periodic refresh; call when the tunnel
    // stops so a stale speed/uptime reading isn't left on screen.
    fun stopSpeedUpdates() {
        handler.removeCallbacks(speedUpdater)
    }

    private fun updateContent(title: String, text: String) {
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, buildNotification(title, text))
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // "Отключить" — lets the user tear the tunnel down straight from the
        // notification shade, no need to reopen the app first. Targets this
        // same service (already started/bound for as long as this
        // notification exists) with a distinct action SocksVpnService.
        // onStartCommand() treats as a stop request, not a (re-)configure.
        val stopIntent = Intent(service, SocksVpnService::class.java).setAction(Constants.ACTION_STOP_VPN)
        val stopPendingIntent = PendingIntent.getService(
            service,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_power_filled, service.getString(R.string.notify_action_disconnect), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = service.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            service.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        mgr.createNotificationChannel(ch)
    }
}
