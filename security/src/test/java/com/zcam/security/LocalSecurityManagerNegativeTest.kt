package com.zcam.security

import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
    fun simplified_pairing_request_requires_server_code_before_trusting_device() = runTest {
        val manager = buildManager()

        val started = manager.requestPairing(
            deviceId = "browser-1",
            displayName = "Browser",
            clientType = PairingClientType.WEB_BROWSER
        )
        assertTrue(started is PairingRequestStartResult.Success)
        started as PairingRequestStartResult.Success

        val pending = manager.pendingPairingRequests
            .first { requests -> requests.any { it.requestId == started.requestId } }
            .single { it.requestId == started.requestId }

        val wrongCode = manager.completePairingRequest(
            requestId = started.requestId,
            verificationCode = "000000"
        )
        assertTrue(wrongCode is PairingResult.Failure)
        wrongCode as PairingResult.Failure
        assertEquals(401, wrongCode.statusCode)
        assertEquals("invalid_pairing_code", wrongCode.reason)

        val success = manager.completePairingRequest(
            requestId = started.requestId,
            verificationCode = pending.verificationCode
        )
        assertTrue(success is PairingResult.Success)
        assertTrue(manager.trustedDevices().any { it.deviceId == "browser-1" })
    }

    @Test
    fun repeated_failed_pairing_codes_trigger_server_cooldown_without_blocking_authenticated_clients() = runTest {
        val manager = buildManager()

        val started = manager.requestPairing(
            deviceId = "browser-lock-test",
            displayName = "Browser",
            clientType = PairingClientType.WEB_BROWSER
        )
        assertTrue(started is PairingRequestStartResult.Success)
        started as PairingRequestStartResult.Success

        repeat(2) {
            val failure = manager.completePairingRequest(
                requestId = started.requestId,
                verificationCode = "000000"
            )
            assertTrue(failure is PairingResult.Failure)
            failure as PairingResult.Failure
            assertEquals(401, failure.statusCode)
            assertEquals("invalid_pairing_code", failure.reason)
        }

        val locked = manager.completePairingRequest(
            requestId = started.requestId,
            verificationCode = "000000"
        )
        assertTrue(locked is PairingResult.Failure)
        locked as PairingResult.Failure
        assertEquals(429, locked.statusCode)
        assertEquals("pairing_locked", locked.reason)
        assertTrue((locked.retryAfterSeconds ?: 0) >= 1)

        val requestDuringCooldown = manager.requestPairing(
            deviceId = "browser-locked-2",
            displayName = "Browser 2",
            clientType = PairingClientType.WEB_BROWSER
        )
        assertTrue(requestDuringCooldown is PairingRequestStartResult.Failure)
        requestDuringCooldown as PairingRequestStartResult.Failure
        assertEquals(429, requestDuringCooldown.statusCode)
        assertEquals("pairing_locked", requestDuringCooldown.reason)

        val auth = manager.authorizeRequest(
            tokenCandidate = RuntimeSettingsDefaults.value.security.apiToken,
            deviceId = null
        )
        assertTrue(auth.allowed)
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
