package com.zcam.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import javax.inject.Inject

@AndroidEntryPoint
class ZCamForegroundService : Service() {

    @Inject
    lateinit var runtimeCoordinator: ZCamRuntimeCoordinator

    @Inject
    lateinit var logger: ZCamLogger

    @Inject
    lateinit var runtimeRestarter: ZCamRuntimeRestarter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        logger.i(LogEventId.SERVICE_CREATED, "Foreground service created")
        ensureNotificationChannel()
        serviceScope.launch {
            runtimeRestarter.requests.collect { command ->
                when (command) {
                    RuntimeControlCommand.Restart -> {
                        try {
                            delay(700L)
                            runtimeCoordinator.stop(persistDesiredState = false)
                            runtimeCoordinator.start()
                        } catch (error: Throwable) {
                            logger.e(LogEventId.COMPONENT_FAILED, error, "Remote runtime restart failed")
                        } finally {
                            runtimeRestarter.finishRestart()
                        }
                    }
                    is RuntimeControlCommand.SetCameraEnabled -> applyCameraEnabled(command.enabled)
                }
            }
        }
        active = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ServiceCommands.ACTION_START
        when (action) {
            ServiceCommands.ACTION_STOP -> {
                logger.i(LogEventId.SERVICE_STOP_REQUESTED, "Camera stop requested; server remains active")
                startForegroundRuntime(cameraEnabled = false)
                serviceScope.launch {
                    applyCameraEnabled(false)
                    runtimeCoordinator.start()
                }
                return START_STICKY
            }
            ServiceCommands.ACTION_CAMERA_ON -> {
                startForegroundRuntime(cameraEnabled = true)
                serviceScope.launch {
                    applyCameraEnabled(true)
                    runtimeCoordinator.start()
                }
                return START_STICKY
            }
            ServiceCommands.ACTION_CAMERA_OFF -> {
                startForegroundRuntime(cameraEnabled = false)
                serviceScope.launch {
                    applyCameraEnabled(false)
                    runtimeCoordinator.start()
                }
                return START_STICKY
            }

            else -> {
                logger.i(LogEventId.SERVICE_START_REQUESTED, "Foreground service start requested")
                val recoveryOnly = action == ServiceCommands.ACTION_RECOVER_SERVER
                startForegroundRuntime(cameraEnabled = !recoveryOnly)
                serviceScope.launch {
                    runCatching {
                        if (recoveryOnly) runtimeCoordinator.setCameraEnabled(false)
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
        active = false
        releaseWakeLock()
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

    private suspend fun applyCameraEnabled(enabled: Boolean) {
        runCatching {
            if (enabled) startForegroundRuntime(cameraEnabled = true)
            runtimeCoordinator.setCameraEnabled(enabled)
            if (!enabled) startForegroundRuntime(cameraEnabled = false)
        }.onFailure { error ->
            logger.e(LogEventId.COMPONENT_FAILED, error, "Camera state update failed")
        }
    }

    private fun startForegroundRuntime(cameraEnabled: Boolean) {
        val notification = NotificationCompat.Builder(this, ServiceCommands.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ZCam active")
            .setContentText(if (cameraEnabled) "Server + camera + loop recording" else "Server active, camera off")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (cameraEnabled) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
        } else {
            0
        }

        ServiceCompat.startForeground(
            this,
            ServiceCommands.NOTIFICATION_ID,
            notification,
            types
        )
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ZCam:ServerRuntime"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        logger.i(LogEventId.SERVICE_START_REQUESTED, "Server CPU wake lock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
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

        fun cameraIntent(context: Context, enabled: Boolean): Intent = Intent(context, ZCamForegroundService::class.java)
            .setAction(if (enabled) ServiceCommands.ACTION_CAMERA_ON else ServiceCommands.ACTION_CAMERA_OFF)

        fun recoveryIntent(context: Context): Intent = Intent(context, ZCamForegroundService::class.java)
            .setAction(ServiceCommands.ACTION_RECOVER_SERVER)

        fun isActive(): Boolean = active

        @Volatile
        private var active: Boolean = false
    }
}
