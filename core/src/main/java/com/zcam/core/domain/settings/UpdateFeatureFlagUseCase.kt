package com.zcam.core.domain.settings

import com.zcam.core.domain.config.FeatureFlag

class UpdateFeatureFlagUseCase(
    private val repository: RuntimeSettingsRepository
) {
    suspend operator fun invoke(flag: FeatureFlag, enabled: Boolean): RuntimeSettingsUpdateResult {
        return repository.setFeatureFlag(flag, enabled)
    }
}
