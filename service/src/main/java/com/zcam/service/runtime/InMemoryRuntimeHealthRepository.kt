package com.zcam.service.runtime

import com.zcam.core.logging.LogEventId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryRuntimeHealthRepository @Inject constructor() : RuntimeHealthRepository {

    private val _health = MutableStateFlow(initialSnapshot())
    override val health: StateFlow<RuntimeHealthSnapshot> = _health.asStateFlow()

    override fun reset() {
        _health.value = initialSnapshot()
    }

    override fun mark(
        component: RuntimeComponent,
        status: ComponentHealthStatus,
        eventId: LogEventId,
        message: String,
        recoveryAttempts: Int
    ) {
        val now = System.currentTimeMillis()
        _health.update { snapshot ->
            val updatedComponent = ComponentHealth(
                component = component,
                status = status,
                lastEventId = eventId.code,
                lastMessage = message,
                lastUpdatedEpochMs = now,
                recoveryAttempts = recoveryAttempts
            )
            val nextComponents = snapshot.components + (component to updatedComponent)
            snapshot.copy(
                overall = deriveOverallStatus(nextComponents.values),
                generatedAtEpochMs = now,
                components = nextComponents
            )
        }
    }

    private fun deriveOverallStatus(components: Collection<ComponentHealth>): RuntimeOverallStatus {
        val active = components.filter { it.status != ComponentHealthStatus.IDLE }
        if (active.isEmpty()) return RuntimeOverallStatus.STOPPED
        if (active.all { it.status == ComponentHealthStatus.STOPPED }) {
            return RuntimeOverallStatus.STOPPED
        }
        if (active.any { it.status == ComponentHealthStatus.FAILED }) {
            return RuntimeOverallStatus.FAILED
        }
        if (active.any { it.status == ComponentHealthStatus.RECOVERING }) {
            return RuntimeOverallStatus.RECOVERING
        }
        if (active.any { it.status == ComponentHealthStatus.STARTING }) {
            return RuntimeOverallStatus.STARTING
        }
        return if (active.all { it.status == ComponentHealthStatus.HEALTHY || it.status == ComponentHealthStatus.STOPPED }) {
            RuntimeOverallStatus.HEALTHY
        } else {
            RuntimeOverallStatus.DEGRADED
        }
    }

    private fun initialSnapshot(): RuntimeHealthSnapshot {
        val now = System.currentTimeMillis()
        return RuntimeHealthSnapshot(
            overall = RuntimeOverallStatus.STOPPED,
            generatedAtEpochMs = now,
            components = RuntimeComponent.entries.associateWith { component ->
                ComponentHealth(
                    component = component,
                    status = ComponentHealthStatus.IDLE,
                    lastEventId = LogEventId.RUNTIME_STOP_SEQUENCE.code,
                    lastMessage = "not started",
                    lastUpdatedEpochMs = now,
                    recoveryAttempts = 0
                )
            }
        )
    }
}
