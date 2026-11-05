package com.zcam.core.domain.settings

import kotlinx.coroutines.flow.Flow

data class RuntimeDesiredState(
    val shouldRun: Boolean,
    val cameraEnabled: Boolean = true,
    val lastChangedAtEpochMs: Long
)

interface RuntimeStateRepository {
    val desiredState: Flow<RuntimeDesiredState>

    suspend fun setDesiredRunning(shouldRun: Boolean)

    suspend fun setCameraEnabled(enabled: Boolean)
}
