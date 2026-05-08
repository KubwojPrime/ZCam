package com.zcam.app

import android.app.Application
import androidx.core.content.ContextCompat
import com.zcam.core.domain.settings.RuntimeCrashRepository
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ReleaseTree
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import com.zcam.service.ZCamForegroundService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ZCamApplication : Application() {

    @Inject
    lateinit var runtimeStateRepository: RuntimeStateRepository

    @Inject
    lateinit var runtimeCrashRepository: RuntimeCrashRepository

    @Inject
    lateinit var logger: ZCamLogger

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree(this))
        }
        installCrashHandler()
        restoreRuntimeIfNeeded()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val reason = "uncaught exception on ${thread.name}: ${throwable::class.java.simpleName}: ${throwable.message}"
                runBlocking(Dispatchers.IO) {
                    runtimeCrashRepository.markCrash(reason)
                }
                logger.e(LogEventId.CRASH_MARKED, throwable, "Crash marker persisted")
            }.onFailure { error ->
                android.util.Log.e("ZCam", "Failed to persist crash marker: ${error.message}", error)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun restoreRuntimeIfNeeded() {
        appScope.launch {
            logger.i(LogEventId.STATE_RESTORE_CHECK, "Process restore check")
            val desiredState = runtimeStateRepository.desiredState.first()
            val crashState = runtimeCrashRepository.state.first()
            val plan = RuntimeRestorePolicy.plan(desiredState.shouldRun, crashState)
            if (plan.shouldStartService) {
                if (plan.shouldMarkCrashRecovery) {
                    logger.w(LogEventId.STATE_RESTORE_CRASH, "Detected dirty runtime marker before process restore")
                    runtimeCrashRepository.markRecovered()
                    logger.i(LogEventId.CRASH_RECOVERY_MARKED, "Crash recovery marker updated")
                }
                logger.i(LogEventId.STATE_RESTORE_START, "Restoring runtime after process restart")
                runCatching {
                    ContextCompat.startForegroundService(
                        this@ZCamApplication,
                        ZCamForegroundService.startIntent(this@ZCamApplication)
                    )
                }.onFailure { error ->
                    logger.e(LogEventId.COMPONENT_FAILED, error, "Process restore failed")
                }
            } else {
                if (plan.shouldClearStaleDirtyMarker) {
                    runtimeCrashRepository.markRuntimeClean()
                    logger.i(LogEventId.CRASH_MARKER_CLEARED, "Cleared stale dirty runtime marker")
                }
                logger.i(LogEventId.STATE_RESTORE_SKIP, "Process restore skipped (desired state off)")
            }
        }
    }
}
