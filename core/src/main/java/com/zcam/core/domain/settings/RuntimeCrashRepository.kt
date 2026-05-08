package com.zcam.core.domain.settings

import kotlinx.coroutines.flow.Flow

data class RuntimeCrashState(
    val runtimeDirty: Boolean = false,
    val lastRuntimeMarkerEpochMs: Long = 0L,
    val lastCrashEpochMs: Long = 0L,
    val lastCrashReason: String? = null,
    val lastRecoveryEpochMs: Long = 0L
)

interface RuntimeCrashRepository {
    val state: Flow<RuntimeCrashState>

    suspend fun markRuntimeDirty()
    suspend fun markRuntimeClean()
    suspend fun markCrash(reason: String)
    suspend fun markRecovered()
}
