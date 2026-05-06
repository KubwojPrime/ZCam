package com.zcam.core.domain.watchdog

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class WatchdogComponentStatus {
    UNKNOWN,
    STARTING,
    HEALTHY,
    STALE,
    RECOVERING,
    STOPPED,
    FAILED
}

enum class RecoveryReason {
    STALE_HEARTBEAT,
    START_FAILURE,
    RUNTIME_EXCEPTION,
    RESOURCE_LOST,
    MANUAL
}

data class RecoveryRequest(
    val component: String,
    val reason: RecoveryReason,
    val details: String,
    val attempt: Int = 0,
    val requestedAtEpochMs: Long = System.currentTimeMillis()
)

data class WatchdogComponentHealth(
    val component: String,
    val status: WatchdogComponentStatus = WatchdogComponentStatus.UNKNOWN,
    val heartbeatTimeoutMs: Long = DEFAULT_HEARTBEAT_TIMEOUT_MS,
    val lastHeartbeatEpochMs: Long = 0L,
    val lastTransitionEpochMs: Long = System.currentTimeMillis(),
    val lastDetails: String? = null
) {
    companion object {
        const val DEFAULT_HEARTBEAT_TIMEOUT_MS = 20_000L
    }
}

data class WatchdogHealthSnapshot(
    val started: Boolean,
    val generatedAtEpochMs: Long,
    val components: Map<String, WatchdogComponentHealth>
)

interface WatchdogEngine {
    val health: StateFlow<WatchdogHealthSnapshot>
    val recoveryEvents: Flow<RecoveryRequest>

    suspend fun start()
    suspend fun stop()

    suspend fun registerComponent(component: String, timeoutMs: Long = WatchdogComponentHealth.DEFAULT_HEARTBEAT_TIMEOUT_MS)
    suspend fun updateComponentStatus(component: String, status: WatchdogComponentStatus, details: String? = null)
    suspend fun heartbeat(component: String)
    suspend fun requestRecovery(request: RecoveryRequest)
}

class RecordHeartbeatUseCase(
    private val engine: WatchdogEngine
) {
    suspend operator fun invoke(component: String) {
        engine.heartbeat(component)
    }
}

class RequestRecoveryUseCase(
    private val engine: WatchdogEngine
) {
    suspend operator fun invoke(component: String, reason: RecoveryReason, details: String) {
        engine.requestRecovery(
            RecoveryRequest(
                component = component,
                reason = reason,
                details = details
            )
        )
    }
}
