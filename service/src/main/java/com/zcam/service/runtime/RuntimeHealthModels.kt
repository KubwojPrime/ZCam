package com.zcam.service.runtime

enum class RuntimeComponent {
    CAMERA,
    SERVER,
    AUDIO,
    STORAGE,
    WATCHDOG,
    THERMAL,
    NETWORK
}

enum class ComponentHealthStatus {
    IDLE,
    STARTING,
    HEALTHY,
    RECOVERING,
    FAILED,
    STOPPING,
    STOPPED
}

data class ComponentHealth(
    val component: RuntimeComponent,
    val status: ComponentHealthStatus,
    val lastEventId: String,
    val lastMessage: String,
    val lastUpdatedEpochMs: Long,
    val recoveryAttempts: Int
)

enum class RuntimeOverallStatus {
    STOPPED,
    STARTING,
    HEALTHY,
    DEGRADED,
    RECOVERING,
    FAILED,
    STOPPING
}

data class RuntimeHealthSnapshot(
    val overall: RuntimeOverallStatus,
    val generatedAtEpochMs: Long,
    val components: Map<RuntimeComponent, ComponentHealth>
)
