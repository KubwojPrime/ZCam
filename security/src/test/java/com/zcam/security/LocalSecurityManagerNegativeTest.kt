package com.zcam.security

import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSecurityManagerNegativeTest {

    @Test
    fun authorize_request_rejects_missing_or_wrong_token() = runTest {
        val manager = buildManager()

        val missing = manager.authorizeRequest(tokenCandidate = null, deviceId = null)
        val invalid = manager.authorizeRequest(tokenCandidate = "bad-token", deviceId = null)

        assertFalse(missing.allowed)
        assertEquals(401, missing.statusCode)
        assertEquals("missing_token", missing.reason)

        assertFalse(invalid.allowed)
        assertEquals(401, invalid.statusCode)
        assertEquals("invalid_token", invalid.reason)
    }

    @Test
    fun pairing_replay_is_rejected() = runTest {
        val manager = buildManager()
        val challenge = manager.createPairingChallenge()

        val first = manager.pairDevice(
            pin = RuntimeSettingsDefaults.value.security.pinCode,
            sessionId = challenge.sessionId,
            pairingCode = challenge.pairingCode,
            deviceId = "device-1",
            displayName = "Phone"
        )
        val replay = manager.pairDevice(
            pin = RuntimeSettingsDefaults.value.security.pinCode,
            sessionId = challenge.sessionId,
            pairingCode = challenge.pairingCode,
            deviceId = "device-1",
            displayName = "Phone"
        )

        assertTrue(first is PairingResult.Success)
        assertTrue(replay is PairingResult.Failure)
        replay as PairingResult.Failure
        assertEquals(409, replay.statusCode)
        assertEquals("pairing_replay_detected", replay.reason)
    }

    @Test
    fun rotates_and_revokes_tokens_locally() = runTest {
        val manager = buildManager()
        val bootstrapToken = RuntimeSettingsDefaults.value.security.apiToken

        val rotate = manager.rotateToken(
            requesterToken = bootstrapToken,
            requesterDeviceId = null,
            revokeCurrent = false
        )
        assertTrue(rotate is TokenRotationResult.Success)
        rotate as TokenRotationResult.Success

        val revoke = manager.revokeToken(
            requesterToken = rotate.tokenValue,
            requesterDeviceId = null,
            tokenIdToRevoke = extractTokenId(bootstrapToken, manager)
        )
        assertTrue(revoke is TokenRevocationResult.Success)

        assertFalse(manager.validateToken(bootstrapToken))
        assertTrue(manager.validateToken(rotate.tokenValue))
    }

    private suspend fun extractTokenId(token: String, manager: LocalSecurityManager): String {
        val auth = manager.authorizeRequest(tokenCandidate = token, deviceId = null)
        return auth.tokenId ?: error("Expected token id for bootstrap token")
    }

    private fun buildManager(): LocalSecurityManager {
        return LocalSecurityManager(
            runtimeSettingsRepository = FakeRuntimeSettingsRepository(RuntimeSettingsDefaults.value),
            tokenStore = InMemoryTokenStore(),
            logger = NoopLogger()
        )
    }

    private class InMemoryTokenStore : SecurityTokenStore {
        private var tokens: List<StoredApiToken> = emptyList()

        override suspend fun readTokens(): List<StoredApiToken> = tokens

        override suspend fun writeTokens(tokens: List<StoredApiToken>) {
            this.tokens = tokens
        }
    }

    private class FakeRuntimeSettingsRepository(
        initial: RuntimeSettings
    ) : RuntimeSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<RuntimeSettings> = state

        override suspend fun updateSettings(candidate: RuntimeSettings): RuntimeSettingsUpdateResult {
            state.value = candidate
            return RuntimeSettingsUpdateResult.Success(candidate)
        }

        override suspend fun setFeatureFlag(
            flag: FeatureFlag,
            enabled: Boolean
        ): RuntimeSettingsUpdateResult {
            val next = state.value.copy(featureFlags = state.value.featureFlags.withFlag(flag, enabled))
            state.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }

        override suspend fun upsertTrustedDevice(device: TrustedDevice): RuntimeSettingsUpdateResult {
            val next = state.value.copy(
                security = state.value.security.copy(
                    trustedDevices = state.value.security.trustedDevices
                        .filterNot { it.deviceId == device.deviceId }
                        .toSet() + device
                )
            )
            state.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }

        override suspend fun removeTrustedDevice(deviceId: String): RuntimeSettingsUpdateResult {
            val next = state.value.copy(
                security = state.value.security.copy(
                    trustedDevices = state.value.security.trustedDevices.filterNot { it.deviceId == deviceId }.toSet()
                )
            )
            state.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }
    }

    private class NoopLogger : com.zcam.core.logging.ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
