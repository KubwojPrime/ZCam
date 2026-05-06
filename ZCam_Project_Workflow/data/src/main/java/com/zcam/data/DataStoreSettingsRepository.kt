package com.zcam.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import com.zcam.core.dispatchers.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

private val Context.zCamDataStore by preferencesDataStore(name = "zcam_settings")

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SettingsRepository {

    override val settings: Flow<ZCamSettings> = context.zCamDataStore.data
        .map { prefs -> prefs.toSettings() }
        .flowOn(ioDispatcher)

    override suspend fun updatePin(pin: String) = withContext(ioDispatcher) {
        context.zCamDataStore.edit { prefs -> prefs[PIN] = pin }
    }

    override suspend fun updateToken(token: String) = withContext(ioDispatcher) {
        context.zCamDataStore.edit { prefs -> prefs[TOKEN] = token }
    }

    override suspend fun updateServerPort(port: Int) = withContext(ioDispatcher) {
        context.zCamDataStore.edit { prefs -> prefs[PORT] = port }
    }

    private fun Preferences.toSettings(): ZCamSettings = ZCamSettings(
        serverPort = this[PORT] ?: 8080,
        streamFps = this[FPS] ?: 15,
        segmentMinutes = this[SEGMENT_MINUTES] ?: 5,
        maxStorageGb = this[MAX_STORAGE_GB] ?: 32,
        minFreeStorageGb = this[MIN_FREE_STORAGE_GB] ?: 5,
        pinCode = this[PIN] ?: "0000",
        apiToken = this[TOKEN] ?: "local-token"
    )

    private companion object {
        val PORT = intPreferencesKey("server_port")
        val FPS = intPreferencesKey("stream_fps")
        val SEGMENT_MINUTES = intPreferencesKey("segment_minutes")
        val MAX_STORAGE_GB = intPreferencesKey("max_storage_gb")
        val MIN_FREE_STORAGE_GB = intPreferencesKey("min_free_storage_gb")
        val PIN = stringPreferencesKey("pin_code")
        val TOKEN = stringPreferencesKey("api_token")
    }
}
