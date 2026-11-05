package com.zcam.core.domain.settings

import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsValidationError
import com.zcam.core.domain.config.TrustedDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface RuntimeSettingsRepository {
    val settings: Flow<RuntimeSettings>

    suspend fun updateSettings(candidate: RuntimeSettings): RuntimeSettingsUpdateResult
    suspend fun updateRemoteSettings(candidate: RuntimeSettings, expectedRevision: String): RuntimeSettingsUpdateResult {
        val current = settings.first()
        if (RemoteServerSettingsCodec.revision(current) != expectedRevision) {
            return RuntimeSettingsUpdateResult.Forbidden("settings_changed_reload_required")
        }
        return updateSettings(candidate.copy(security = current.security,
            featureFlags = candidate.featureFlags.copy(trustedDevices = current.featureFlags.trustedDevices)))
    }
    suspend fun setFeatureFlag(flag: FeatureFlag, enabled: Boolean): RuntimeSettingsUpdateResult
    suspend fun upsertTrustedDevice(device: TrustedDevice): RuntimeSettingsUpdateResult
    suspend fun removeTrustedDevice(deviceId: String): RuntimeSettingsUpdateResult
}

sealed interface RuntimeSettingsUpdateResult {
    data class Success(val settings: RuntimeSettings) : RuntimeSettingsUpdateResult
    data class ValidationFailed(val errors: List<RuntimeSettingsValidationError>) : RuntimeSettingsUpdateResult
    data class Forbidden(val reason: String) : RuntimeSettingsUpdateResult
}
