package com.zcam.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcam.core.dispatchers.IoDispatcher
import com.zcam.core.domain.settings.RuntimeCrashRepository
import com.zcam.core.domain.settings.RuntimeCrashState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.runtimeCrashDataStore by preferencesDataStore(name = "zcam_runtime_crash_state")

@Singleton
class DataStoreRuntimeCrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RuntimeCrashRepository {

    override val state: Flow<RuntimeCrashState> = context.runtimeCrashDataStore.data
        .map { prefs ->
            RuntimeCrashState(
                runtimeDirty = prefs[RUNTIME_DIRTY] ?: false,
                lastRuntimeMarkerEpochMs = prefs[LAST_RUNTIME_MARKER] ?: 0L,
                lastCrashEpochMs = prefs[LAST_CRASH] ?: 0L,
                lastCrashReason = prefs[LAST_CRASH_REASON],
                lastRecoveryEpochMs = prefs[LAST_RECOVERY] ?: 0L
            )
        }
        .flowOn(ioDispatcher)

    override suspend fun markRuntimeDirty() {
        val now = System.currentTimeMillis()
        withContext(ioDispatcher) {
            context.runtimeCrashDataStore.edit { prefs ->
                prefs[RUNTIME_DIRTY] = true
                prefs[LAST_RUNTIME_MARKER] = now
            }
        }
    }

    override suspend fun markRuntimeClean() {
        val now = System.currentTimeMillis()
        withContext(ioDispatcher) {
            context.runtimeCrashDataStore.edit { prefs ->
                prefs[RUNTIME_DIRTY] = false
                prefs[LAST_RUNTIME_MARKER] = now
            }
        }
    }

    override suspend fun markCrash(reason: String) {
        val now = System.currentTimeMillis()
        withContext(ioDispatcher) {
            context.runtimeCrashDataStore.edit { prefs ->
                prefs[RUNTIME_DIRTY] = true
                prefs[LAST_RUNTIME_MARKER] = now
                prefs[LAST_CRASH] = now
                prefs[LAST_CRASH_REASON] = reason.take(MAX_REASON_LENGTH)
            }
        }
    }

    override suspend fun markRecovered() {
        withContext(ioDispatcher) {
            context.runtimeCrashDataStore.edit { prefs ->
                prefs[LAST_RECOVERY] = System.currentTimeMillis()
            }
        }
    }

    private companion object {
        const val MAX_REASON_LENGTH = 512

        val RUNTIME_DIRTY = booleanPreferencesKey("runtime_dirty")
        val LAST_RUNTIME_MARKER = longPreferencesKey("runtime_marker_epoch_ms")
        val LAST_CRASH = longPreferencesKey("last_crash_epoch_ms")
        val LAST_CRASH_REASON = stringPreferencesKey("last_crash_reason")
        val LAST_RECOVERY = longPreferencesKey("last_recovery_epoch_ms")
    }
}
