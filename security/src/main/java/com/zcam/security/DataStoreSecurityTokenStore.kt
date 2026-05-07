package com.zcam.security

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcam.core.dispatchers.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.securityTokenStoreDataStore by preferencesDataStore(name = "zcam_security_tokens")

@Singleton
internal class DataStoreSecurityTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : SecurityTokenStore {

    override suspend fun readTokens(): List<StoredApiToken> = withContext(ioDispatcher) {
        val rawTokens = context.securityTokenStoreDataStore.data.first()[TOKENS].orEmpty()
        rawTokens.mapNotNull { decodeToken(it) }
            .distinctBy { it.tokenId }
            .sortedBy { it.issuedAtEpochMs }
    }

    override suspend fun writeTokens(tokens: List<StoredApiToken>) {
        withContext(ioDispatcher) {
        context.securityTokenStoreDataStore.edit { prefs ->
            prefs[TOKENS] = tokens
                .distinctBy { it.tokenId }
                .sortedBy { it.issuedAtEpochMs }
                .mapTo(mutableSetOf()) { encodeToken(it) }
        }
        }
    }

    private fun encodeToken(token: StoredApiToken): String {
        val id = base64Encode(token.tokenId)
        val hash = base64Encode(token.tokenHash)
        val device = base64Encode(token.boundDeviceId ?: "")
        val issued = token.issuedAtEpochMs.toString()
        val revoked = (token.revokedAtEpochMs ?: 0L).toString()
        return "$id|$hash|$device|$issued|$revoked"
    }

    private fun decodeToken(raw: String): StoredApiToken? {
        val parts = raw.split('|', limit = 5)
        if (parts.size != 5) return null
        return runCatching {
            val id = base64Decode(parts[0])
            val hash = base64Decode(parts[1])
            val device = base64Decode(parts[2]).ifBlank { null }
            val issuedAt = parts[3].toLong()
            val revokedAt = parts[4].toLong().takeIf { it > 0L }
            StoredApiToken(
                tokenId = id,
                tokenHash = hash,
                boundDeviceId = device,
                issuedAtEpochMs = issuedAt,
                revokedAtEpochMs = revokedAt
            )
        }.getOrNull()
    }

    private fun base64Encode(value: String): String {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(value.toByteArray(Charsets.UTF_8))
    }

    private fun base64Decode(value: String): String {
        return Base64.getUrlDecoder().decode(value).toString(Charsets.UTF_8)
    }

    private companion object {
        val TOKENS = stringSetPreferencesKey("security_tokens_v1")
    }
}
