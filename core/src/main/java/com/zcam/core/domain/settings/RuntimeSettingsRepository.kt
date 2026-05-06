package com.zcam.core.domain.settings

import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsValidationError
import com.zcam.core.domain.config.TrustedDevice
import kotlinx.coroutines.flow.Flow

interface RuntimeSettingsRepository {
    val settings: Flow<RuntimeSettings>

    suspend fun updateSettings(candidate: RuntimeSettings): RuntimeSettingsUpdateResult
    suspend fun setFeatureFlag(flag: FeatureFlag, enabled: Boolean): RuntimeSettingsUpdateResult
    suspend fun upsertTrustedDevice(device: TrustedDevice): RuntimeSettingsUpdateResult
    suspend fun removeTrustedDevice(deviceId: String): RuntimeSettingsUpdateResult
}

sealed interface RuntimeSettingsUpdateResult {
    data class Success(val settings: RuntimeSettings) : RuntimeSettingsUpdateResult
    data class ValidationFailed(val errors: List<RuntimeSettingsValidationError>) : RuntimeSettingsUpdateResult
    data class Forbidden(val reason: String) : RuntimeSettingsUpdateResult
}
