package com.zcam.watchdog

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessWatchdogManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : WatchdogManager {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val heartbeats = ConcurrentHashMap<String, Long>()
    private var watchJob: Job? = null

    override suspend fun start() = withContext(dispatchers.default) {
        if (watchJob?.isActive == true) return@withContext

        watchJob = scope.launch {
            logger.i("Watchdog started")
            while (isActive) {
                val now = System.currentTimeMillis()
                heartbeats.forEach { (component, timestamp) ->
                    if (now - timestamp > TIMEOUT_MS) {
                        logger.w("Watchdog warning: $component stale for ${now - timestamp} ms")
                    }
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    override suspend fun stop() = withContext(dispatchers.default) {
        watchJob?.cancel()
        watchJob = null
        logger.i("Watchdog stopped")
    }

    override fun signalHeartbeat(component: String) {
        heartbeats[component] = System.currentTimeMillis()
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 5_000L
        const val TIMEOUT_MS = 20_000L
    }
}
