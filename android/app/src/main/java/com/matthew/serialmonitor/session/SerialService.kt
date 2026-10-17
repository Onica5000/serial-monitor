package com.matthew.serialmonitor.session

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.matthew.serialmonitor.R
import com.matthew.serialmonitor.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Foreground service that keeps the process (and the serial read loop) alive while connected. */
class SerialService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = SerialSession.get(this)
        if (intent?.action == ACTION_DISCONNECT) {
            session.disconnect()
            stopSelfSafely()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, build(session.status.value, session.stats.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException; the connection still works while visible.
            session.info("Background service unavailable: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            scope.launch {
                combine(session.status, session.stats) { s, st -> s to st }.collect { (s, st) ->
                    if (s.state == ConnState.DISCONNECTED) stopSelfSafely()
                    else getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(s, st))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun build(s: ConnStatus, st: Stats): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, SerialService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (s.state) {
            ConnState.CONNECTED -> "Connected: ${s.target?.label ?: ""}"
            ConnState.RECONNECTING -> "Reconnecting: ${s.target?.label ?: ""}"
            ConnState.CONNECTING -> "Connecting: ${s.target?.label ?: ""}"
            ConnState.DISCONNECTED -> "Disconnected"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_serial)
            .setContentTitle(title)
            .setContentText("${s.description} · RX ${st.rxBytes} B · TX ${st.txBytes} B")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "serial"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.matthew.serialmonitor.DISCONNECT"

        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Serial connection", NotificationManager.IMPORTANCE_LOW))
            }
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, SerialService::class.java)) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, SerialService::class.java)) } }
    }
}
