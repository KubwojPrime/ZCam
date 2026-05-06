package com.zcam.service.runtime

import com.zcam.core.logging.LogEventId
import kotlinx.coroutines.flow.StateFlow

interface RuntimeHealthRepository {
    val health: StateFlow<RuntimeHealthSnapshot>

    fun reset()
    fun mark(
        component: RuntimeComponent,
        status: ComponentHealthStatus,
        eventId: LogEventId,
        message: String,
        recoveryAttempts: Int = 0
    )
}
