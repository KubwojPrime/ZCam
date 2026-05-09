package com.zcam.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcam.core.dispatchers.IoDispatcher
import com.zcam.core.domain.settings.ClientSession
import com.zcam.core.domain.settings.ClientSessionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.clientSessionDataStore by preferencesDataStore(name = "zcam_client_session")

@Singleton
class DataStoreClientSessionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ClientSessionRepository {

    override val session: Flow<ClientSession> = context.clientSessionDataStore.data
        .map { prefs ->
            ClientSession(
                serverHost = prefs[SERVER_HOST].orEmpty(),
                serverPort = prefs[SERVER_PORT] ?: 8080,
                deviceId = prefs[DEVICE_ID].orEmpty(),
                displayName = prefs[DISPLAY_NAME].orEmpty(),
                issuedToken = prefs[ISSUED_TOKEN].orEmpty(),
                pairedAtEpochMs = prefs[PAIRED_AT_EPOCH_MS] ?: 0L,
                lastUpdatedAtEpochMs = prefs[LAST_UPDATED_AT_EPOCH_MS] ?: 0L,
                lastModeName = prefs[LAST_MODE_NAME].orEmpty()
            )
        }
        .flowOn(ioDispatcher)

    override suspend fun saveSession(session: ClientSession) {
        withContext(ioDispatcher) {
            context.clientSessionDataStore.edit { prefs ->
                prefs[SERVER_HOST] = session.serverHost
                prefs[SERVER_PORT] = session.serverPort
                prefs[DEVICE_ID] = session.deviceId
                prefs[DISPLAY_NAME] = session.displayName
                prefs[ISSUED_TOKEN] = session.issuedToken
                prefs[PAIRED_AT_EPOCH_MS] = session.pairedAtEpochMs
                prefs[LAST_UPDATED_AT_EPOCH_MS] = session.lastUpdatedAtEpochMs
                prefs[LAST_MODE_NAME] = session.lastModeName
            }
        }
    }

    override suspend fun clearIssuedToken() {
        withContext(ioDispatcher) {
            context.clientSessionDataStore.edit { prefs ->
                prefs[ISSUED_TOKEN] = ""
                prefs[PAIRED_AT_EPOCH_MS] = 0L
                prefs[LAST_UPDATED_AT_EPOCH_MS] = System.currentTimeMillis()
            }
        }
    }

    private companion object {
        val SERVER_HOST = stringPreferencesKey("server_host")
        val SERVER_PORT = intPreferencesKey("server_port")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val ISSUED_TOKEN = stringPreferencesKey("issued_token")
        val PAIRED_AT_EPOCH_MS = longPreferencesKey("paired_at_epoch_ms")
        val LAST_UPDATED_AT_EPOCH_MS = longPreferencesKey("last_updated_at_epoch_ms")
        val LAST_MODE_NAME = stringPreferencesKey("last_mode_name")
    }
}
