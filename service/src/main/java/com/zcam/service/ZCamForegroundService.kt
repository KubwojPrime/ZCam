package com.zcam.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ZCamForegroundService : Service() {

    @Inject
    lateinit var runtimeCoordinator: ZCamRuntimeCoordinator

    @Inject
    lateinit var logger: ZCamLogger

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        logger.i(LogEventId.SERVICE_CREATED, "Foreground service created")
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ServiceCommands.ACTION_START
        when (action) {
            ServiceCommands.ACTION_STOP -> {
                logger.i(LogEventId.SERVICE_STOP_REQUESTED, "Foreground service stop requested")
                serviceScope.launch {
                    runCatching {
                        runtimeCoordinator.stop(persistDesiredState = true)
                    }.onFailure { error ->
                        logger.e(LogEventId.COMPONENT_FAILED, error, "Runtime stop failed")
                    }
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            else -> {
                logger.i(LogEventId.SERVICE_START_REQUESTED, "Foreground service start requested")
                startForegroundRuntime()
                serviceScope.launch {
                    runCatching {
                        runtimeCoordinator.start()
                    }.onFailure { error ->
                        logger.e(LogEventId.COMPONENT_FAILED, error, "Runtime start failed")
                    }
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                runtimeCoordinator.stop(persistDesiredState = false)
            }.onFailure { error ->
                logger.e(LogEventId.COMPONENT_FAILED, error, "Runtime forced stop failed")
            }
        }
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun startForegroundRuntime() {
        val notification = NotificationCompat.Builder(this, ServiceCommands.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ZCam active")
            .setContentText("Camera + MJPEG + loop recording")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            ServiceCommands.NOTIFICATION_ID,
            notification,
            types
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            ServiceCommands.NOTIFICATION_CHANNEL_ID,
            ServiceCommands.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        fun startIntent(context: Context): Intent = Intent(context, ZCamForegroundService::class.java)
            .setAction(ServiceCommands.ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, ZCamForegroundService::class.java)
            .setAction(ServiceCommands.ACTION_STOP)
    }
}
