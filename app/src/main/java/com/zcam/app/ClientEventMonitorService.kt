package com.zcam.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zcam.client.ClientCallResult
import com.zcam.client.ClientRecordingEvent
import com.zcam.client.ClientTarget
import com.zcam.client.LocalClient
import com.zcam.core.domain.settings.ClientSessionRepository
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.w
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ClientEventMonitorService : Service() {

    @Inject lateinit var client: LocalClient
    @Inject lateinit var sessions: ClientSessionRepository
    @Inject lateinit var logger: ZCamLogger

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private val preferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL, "ZCam event monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(EVENT_CHANNEL, "ZCam motion events", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setEnabled(this, true)
        val notification = NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ZCam event alerts")
            .setContentText("Monitoring the paired server over LAN/VPN")
            .setOngoing(true)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, MONITOR_NOTIFICATION_ID, notification, serviceType)
        monitorJob?.cancel()
        monitorJob = scope.launch { monitorEvents() }
        return START_STICKY
    }

    private suspend fun monitorEvents() {
        var pollDelayMs = POLL_INTERVAL_MS
        while (scope.isActive) {
            try {
                val session = sessions.session.first()
                if (session.serverHost.isNotBlank() && session.issuedToken.isNotBlank() && session.serverPort in 1..65535) {
                    val target = ClientTarget(session.serverHost, session.serverPort, session.issuedToken, session.deviceId)
                    val cursorKey = "cursor_${session.serverHost}_${session.serverPort}_${session.deviceId}"
                    if (!preferences.contains(cursorKey)) {
                        preferences.edit().putLong(cursorKey, System.currentTimeMillis()).apply()
                    }
                    val lastSeen = preferences.getLong(cursorKey, 0L)
                    when (val result = client.fetchRecordingEvents(target, fromEpochMs = lastSeen + 1L, limit = 100)) {
                        is ClientCallResult.Success -> {
                            pollDelayMs = POLL_INTERVAL_MS
                            result.value.sortedBy(ClientRecordingEvent::epochMs).forEach { event ->
                                if (event.epochMs > preferences.getLong(cursorKey, 0L)) {
                                    showEventNotification(target, event)
                                    preferences.edit().putLong(cursorKey, event.epochMs).apply()
                                }
                            }
                        }
                        is ClientCallResult.Failure -> {
                            pollDelayMs = (pollDelayMs * 2L).coerceAtMost(MAX_POLL_BACKOFF_MS)
                        }
                    }
                } else {
                    pollDelayMs = MISSING_SESSION_POLL_INTERVAL_MS
                }
            } catch (error: Exception) {
                logger.w("Client event monitor failed: ${error.message}")
                pollDelayMs = (pollDelayMs * 2L).coerceAtMost(MAX_POLL_BACKOFF_MS)
            }
            delay(pollDelayMs)
        }
    }

    private suspend fun showEventNotification(target: ClientTarget, event: ClientRecordingEvent) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cameraTamper = event.type.equals("camera_tamper", ignoreCase = true)
        val builder = NotificationCompat.Builder(this, EVENT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (cameraTamper) "Camera interference detected" else "Moving object detected")
            .setContentText(
                if (cameraTamper) "Check the ZCam camera at ${target.host}" else "ZCam server: ${target.host}"
            )
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setWhen(event.epochMs)

        val frame = client.fetchEventFrame(target, event.epochMs)
        if (frame is ClientCallResult.Success) {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeByteArray(frame.value, 0, frame.value.size, options)?.let { bitmap ->
                builder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
            }
        }
        NotificationManagerCompat.from(this).notify(event.epochMs.hashCode(), builder.build())
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val PREFERENCES_NAME = "zcam_client_event_alerts"
        const val ENABLED_KEY = "enabled"
        private const val ACTION_START = "com.zcam.client.EVENT_MONITOR_START"
        private const val MONITOR_CHANNEL = "zcam_client_monitor"
        private const val EVENT_CHANNEL = "zcam_events"
        private const val MONITOR_NOTIFICATION_ID = 4102
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MISSING_SESSION_POLL_INTERVAL_MS = 30_000L
        private const val MAX_POLL_BACKOFF_MS = 60_000L

        fun startIntent(context: Context): Intent = Intent(context, ClientEventMonitorService::class.java).setAction(ACTION_START)
        fun serviceIntent(context: Context): Intent = Intent(context, ClientEventMonitorService::class.java)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(ENABLED_KEY, enabled)
                .apply()
        }

        fun restoreIfEnabled(context: Context, logger: ZCamLogger) {
            val enabled = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getBoolean(ENABLED_KEY, false)
            if (!enabled) return
            runCatching {
                ContextCompat.startForegroundService(context, startIntent(context))
            }.onFailure { error ->
                logger.w("Client event monitor restore failed: ${error.message}")
            }
        }
    }
}
