package com.zcam.app

import android.app.Application
import androidx.core.content.ContextCompat
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ReleaseTree
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.service.ZCamForegroundService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ZCamApplication : Application() {

    @Inject
    lateinit var runtimeStateRepository: RuntimeStateRepository

    @Inject
    lateinit var logger: ZCamLogger

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(ReleaseTree())
        }
        restoreRuntimeIfNeeded()
    }

    private fun restoreRuntimeIfNeeded() {
        appScope.launch {
            logger.i(LogEventId.STATE_RESTORE_CHECK, "Process restore check")
            val desiredState = runtimeStateRepository.desiredState.first()
            if (desiredState.shouldRun) {
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
                logger.i(LogEventId.STATE_RESTORE_SKIP, "Process restore skipped (desired state off)")
            }
        }
    }
}
