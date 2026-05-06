package com.zcam.core.domain.settings

import com.zcam.core.domain.config.RuntimeSettings

class UpdateRuntimeSettingsUseCase(
    private val repository: RuntimeSettingsRepository
) {
    suspend operator fun invoke(candidate: RuntimeSettings): RuntimeSettingsUpdateResult {
        return repository.updateSettings(candidate)
    }
}
