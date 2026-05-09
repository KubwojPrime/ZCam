package com.zcam.core.domain.settings

import kotlinx.coroutines.flow.Flow

data class ClientSession(
    val serverHost: String = "",
    val serverPort: Int = 8080,
    val deviceId: String = "",
    val displayName: String = "",
    val issuedToken: String = "",
    val pairedAtEpochMs: Long = 0L,
    val lastUpdatedAtEpochMs: Long = 0L
)

interface ClientSessionRepository {
    val session: Flow<ClientSession>

    suspend fun saveSession(session: ClientSession)

    suspend fun clearIssuedToken()
}
