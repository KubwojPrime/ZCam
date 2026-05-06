package com.zcam.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcam.core.dispatchers.IoDispatcher
import com.zcam.core.domain.settings.RuntimeDesiredState
import com.zcam.core.domain.settings.RuntimeStateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.runtimeStateDataStore by preferencesDataStore(name = "zcam_runtime_state")

@Singleton
class DataStoreRuntimeStateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RuntimeStateRepository {

    override val desiredState: Flow<RuntimeDesiredState> = context.runtimeStateDataStore.data
        .map { prefs ->
            RuntimeDesiredState(
                shouldRun = prefs[DESIRED_RUNNING] ?: false,
                lastChangedAtEpochMs = prefs[LAST_CHANGED] ?: 0L
            )
        }
        .flowOn(ioDispatcher)

    override suspend fun setDesiredRunning(shouldRun: Boolean) {
        withContext(ioDispatcher) {
            context.runtimeStateDataStore.edit { prefs ->
                prefs[DESIRED_RUNNING] = shouldRun
                prefs[LAST_CHANGED] = System.currentTimeMillis()
            }
        }
    }

    private companion object {
        val DESIRED_RUNNING = booleanPreferencesKey("desired_running")
        val LAST_CHANGED = longPreferencesKey("last_changed_epoch_ms")
    }
}
