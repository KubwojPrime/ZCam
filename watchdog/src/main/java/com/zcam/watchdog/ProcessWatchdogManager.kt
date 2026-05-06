package com.zcam.watchdog

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.watchdog.RecoveryReason
import com.zcam.core.domain.watchdog.RecoveryRequest
import com.zcam.core.domain.watchdog.WatchdogComponentHealth
import com.zcam.core.domain.watchdog.WatchdogComponentStatus
import com.zcam.core.domain.watchdog.WatchdogHealthSnapshot
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessWatchdogManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : WatchdogManager {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val _health = MutableStateFlow(
        WatchdogHealthSnapshot(
            started = false,
            generatedAtEpochMs = System.currentTimeMillis(),
            components = emptyMap()
        )
    )
    private val _recoveryEvents = MutableSharedFlow<RecoveryRequest>(extraBufferCapacity = 32)

    private var watchJob: Job? = null

    override val health: StateFlow<WatchdogHealthSnapshot> = _health.asStateFlow()
    override val recoveryEvents: Flow<RecoveryRequest> = _recoveryEvents.asSharedFlow()

    override suspend fun start() = withContext(dispatchers.default) {
        if (watchJob?.isActive == true) return@withContext

        _health.update {
            it.copy(started = true, generatedAtEpochMs = System.currentTimeMillis())
        }

        watchJob = scope.launch {
            logger.i(LogEventId.WATCHDOG_STARTED, "Watchdog loop started")
            while (isActive) {
                evaluateStaleComponents()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    override suspend fun stop() = withContext(dispatchers.default) {
        watchJob?.cancel()
        watchJob = null

        val now = System.currentTimeMillis()
        _health.update { snapshot ->
            snapshot.copy(
                started = false,
                generatedAtEpochMs = now,
                components = snapshot.components.mapValues { (_, component) ->
                    component.copy(
                        status = WatchdogComponentStatus.STOPPED,
                        lastTransitionEpochMs = now,
                        lastDetails = "watchdog stopped"
                    )
                }
            )
        }

        logger.i(LogEventId.WATCHDOG_STOPPED, "Watchdog loop stopped")
    }

    override suspend fun registerComponent(component: String, timeoutMs: Long) = withContext(dispatchers.default) {
        val now = System.currentTimeMillis()
        _health.update { snapshot ->
            snapshot.copy(
                generatedAtEpochMs = now,
                components = snapshot.components + (
                    component to WatchdogComponentHealth(
                        component = component,
                        status = WatchdogComponentStatus.STARTING,
                        heartbeatTimeoutMs = timeoutMs,
                        lastTransitionEpochMs = now,
                        lastDetails = "registered"
                    )
                    )
            )
        }
    }

    override suspend fun updateComponentStatus(
        component: String,
        status: WatchdogComponentStatus,
        details: String?
    ) = withContext(dispatchers.default) {
        val now = System.currentTimeMillis()
        _health.update { snapshot ->
            val existing = snapshot.components[component] ?: WatchdogComponentHealth(component = component)
            snapshot.copy(
                generatedAtEpochMs = now,
                components = snapshot.components + (
                    component to existing.copy(
                        status = status,
                        lastTransitionEpochMs = now,
                        lastDetails = details ?: existing.lastDetails
                    )
                    )
            )
        }
    }

    override suspend fun heartbeat(component: String) = withContext(dispatchers.default) {
        val now = System.currentTimeMillis()
        _health.update { snapshot ->
            val existing = snapshot.components[component] ?: WatchdogComponentHealth(component = component)
            snapshot.copy(
                generatedAtEpochMs = now,
                components = snapshot.components + (
                    component to existing.copy(
                        status = WatchdogComponentStatus.HEALTHY,
                        lastHeartbeatEpochMs = now,
                        lastTransitionEpochMs = now,
                        lastDetails = "heartbeat"
                    )
                    )
            )
        }
    }

    override suspend fun requestRecovery(request: RecoveryRequest) = withContext(dispatchers.default) {
        updateComponentStatus(
            component = request.component,
            status = WatchdogComponentStatus.RECOVERING,
            details = request.details
        )
        _recoveryEvents.emit(request)
    }

    private suspend fun evaluateStaleComponents() {
        val now = System.currentTimeMillis()
        val snapshot = _health.value
        if (!snapshot.started) return

        snapshot.components.values.forEach { componentHealth ->
            val isTerminal = componentHealth.status == WatchdogComponentStatus.STOPPED ||
                componentHealth.status == WatchdogComponentStatus.RECOVERING ||
                componentHealth.status == WatchdogComponentStatus.STALE
            if (isTerminal) return@forEach

            val heartbeatAge = now - componentHealth.lastHeartbeatEpochMs
            val hasTimedOut = componentHealth.lastHeartbeatEpochMs > 0L && heartbeatAge > componentHealth.heartbeatTimeoutMs
            if (!hasTimedOut) return@forEach

            updateComponentStatus(
                component = componentHealth.component,
                status = WatchdogComponentStatus.STALE,
                details = "no heartbeat for ${heartbeatAge} ms"
            )

            val request = RecoveryRequest(
                component = componentHealth.component,
                reason = RecoveryReason.STALE_HEARTBEAT,
                details = "Watchdog timeout after ${heartbeatAge} ms"
            )
            _recoveryEvents.emit(request)
            logger.w(
                LogEventId.WATCHDOG_STALE,
                "Stale component detected: ${componentHealth.component}, age=${heartbeatAge}ms"
            )
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 2_000L
    }
}
