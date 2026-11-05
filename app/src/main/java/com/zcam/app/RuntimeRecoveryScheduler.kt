package com.zcam.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.service.ZCamForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

object RuntimeRecoveryScheduler {
    private const val INTERVAL_MS = 5 * 60_000L
    private const val REQUEST_CODE = 5105

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, RuntimeRecoveryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            pendingIntent
        )
    }
}

@AndroidEntryPoint
class RuntimeRecoveryReceiver : BroadcastReceiver() {
    @Inject lateinit var runtimeStateRepository: RuntimeStateRepository
    @Inject lateinit var logger: ZCamLogger

    override fun onReceive(context: Context, intent: Intent?) {
        if (ZCamForegroundService.isActive()) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runtimeStateRepository.setDesiredRunning(true)
                runtimeStateRepository.setCameraEnabled(false)
                ContextCompat.startForegroundService(context, ZCamForegroundService.recoveryIntent(context))
                logger.i(LogEventId.STATE_RESTORE_START, "Five-minute recovery restored server with camera off")
            } catch (error: Throwable) {
                logger.e(LogEventId.COMPONENT_FAILED, error, "Five-minute server recovery failed")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
