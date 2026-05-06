package com.zcam.core.domain.settings

import kotlinx.coroutines.flow.Flow

data class RuntimeDesiredState(
    val shouldRun: Boolean,
    val lastChangedAtEpochMs: Long
)

interface RuntimeStateRepository {
    val desiredState: Flow<RuntimeDesiredState>

    suspend fun setDesiredRunning(shouldRun: Boolean)
}
