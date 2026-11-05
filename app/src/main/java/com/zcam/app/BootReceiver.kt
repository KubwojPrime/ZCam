package com.zcam.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcam.core.domain.settings.RuntimeCrashRepository
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var runtimeStateRepository: RuntimeStateRepository

    @Inject
    lateinit var runtimeCrashRepository: RuntimeCrashRepository

    @Inject
    lateinit var logger: ZCamLogger

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                RuntimeRecoveryScheduler.schedule(context)
                logger.i(LogEventId.STATE_RESTORE_CHECK, "Boot restore check")
                runtimeStateRepository.setDesiredRunning(true)
                runtimeStateRepository.setCameraEnabled(false)
                val desiredState = runtimeStateRepository.desiredState.first()
                val crashState = runtimeCrashRepository.state.first()
                val plan = RuntimeRestorePolicy.plan(desiredState.shouldRun, crashState)
                if (plan.shouldStartService) {
                    if (plan.shouldMarkCrashRecovery) {
                        logger.w(LogEventId.STATE_RESTORE_CRASH, "Boot restore after unclean runtime stop")
                        runtimeCrashRepository.markRecovered()
                    }
                    logger.i(LogEventId.STATE_RESTORE_START, "Restoring runtime after boot")
                    runCatching {
                        RuntimeRecoveryStarter.request(context)
                    }.onFailure { error ->
                        logger.e(LogEventId.COMPONENT_FAILED, error, "Boot restore failed")
                    }
                } else {
                    if (plan.shouldClearStaleDirtyMarker) {
                        runtimeCrashRepository.markRuntimeClean()
                        logger.i(LogEventId.CRASH_MARKER_CLEARED, "Boot cleared stale dirty runtime marker")
                    }
                    logger.i(LogEventId.STATE_RESTORE_SKIP, "Boot restore skipped (desired state off)")
                }
                ClientEventMonitorService.restoreIfEnabled(context, logger)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
