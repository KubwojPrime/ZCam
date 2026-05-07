package com.zcam.security

data class StoredApiToken(
    val tokenId: String,
    val tokenHash: String,
    val boundDeviceId: String?,
    val issuedAtEpochMs: Long,
    val revokedAtEpochMs: Long? = null
) {
    val isActive: Boolean get() = revokedAtEpochMs == null
}

interface SecurityTokenStore {
    suspend fun readTokens(): List<StoredApiToken>
    suspend fun writeTokens(tokens: List<StoredApiToken>)
}
