package com.zcam.core.domain.settings

import com.zcam.core.domain.config.RuntimeSettingsDefaults
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.TrustedDevice

class RemoteServerSettingsCodecTest {
    private val base = RuntimeSettingsDefaults.value

    @Test
    fun remote_update_preserves_credentials_and_trusted_devices() {
        val private = base.copy(security = base.security.copy(pinCode = "123456", apiToken = "private-secret-token"))
        val wire = RemoteServerSettingsCodec.encode(private)
        assertFalse(wire.values.contains("123456"))
        assertFalse(wire.values.contains("private-secret-token"))
        val updated = RemoteServerSettingsCodec.decode(wire + ("streamFps" to "20"), private)
        assertEquals(20, updated.stream.fps)
        assertEquals(private.security, updated.security)
        assertEquals(base, RemoteServerSettingsCodec.decode(RemoteServerSettingsCodec.encode(base)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_secret_or_policy_fields() {
        RemoteServerSettingsCodec.decode(RemoteServerSettingsCodec.encode(base) + ("apiToken" to "attacker-token"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_invalid_operational_values() {
        RemoteServerSettingsCodec.decode(RemoteServerSettingsCodec.encode(base) + ("streamFps" to "0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejects_invalid_resolution_before_saving() {
        RemoteServerSettingsCodec.decode(RemoteServerSettingsCodec.encode(base) + ("streamWidth" to "-1"))
    }

    @Test
    fun revision_changes_with_operational_settings() {
        assertEquals(RemoteServerSettingsCodec.revision(base), RemoteServerSettingsCodec.revision(base.copy()))
        assertNotEquals(RemoteServerSettingsCodec.revision(base), RemoteServerSettingsCodec.revision(base.copy(serverPort = 9090)))
    }

    @Test
    fun remote_repository_contract_refuses_stale_edits_and_preserves_current_private_state() = runBlocking {
        val private = base.copy(security = base.security.copy(apiToken = "current-private-token"))
        val repository = MemorySettings(private)
        val revision = RemoteServerSettingsCodec.revision(private)
        val candidate = base.copy(stream = base.stream.copy(fps = 20), featureFlags = base.featureFlags.copy(trustedDevices = false))
        val saved = repository.updateRemoteSettings(candidate, revision) as RuntimeSettingsUpdateResult.Success
        assertEquals(20, saved.settings.stream.fps)
        assertEquals(private.security, saved.settings.security)
        assertTrue(saved.settings.featureFlags.trustedDevices)
        val stale = repository.updateRemoteSettings(base, revision)
        assertEquals(RuntimeSettingsUpdateResult.Forbidden("settings_changed_reload_required"), stale)
        assertEquals(saved.settings, repository.settings.value)
    }

    private class MemorySettings(initial: RuntimeSettings) : RuntimeSettingsRepository {
        override val settings = MutableStateFlow(initial)
        override suspend fun updateSettings(candidate: RuntimeSettings): RuntimeSettingsUpdateResult {
            settings.value = candidate
            return RuntimeSettingsUpdateResult.Success(candidate)
        }
        override suspend fun setFeatureFlag(flag: FeatureFlag, enabled: Boolean): RuntimeSettingsUpdateResult = error("unused")
        override suspend fun upsertTrustedDevice(device: TrustedDevice): RuntimeSettingsUpdateResult = error("unused")
        override suspend fun removeTrustedDevice(deviceId: String): RuntimeSettingsUpdateResult = error("unused")
    }
}
