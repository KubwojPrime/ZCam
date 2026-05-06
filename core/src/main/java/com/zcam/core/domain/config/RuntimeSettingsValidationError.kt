package com.zcam.core.domain.config

sealed interface RuntimeSettingsValidationError {
    data class InvalidServerPort(val value: Int) : RuntimeSettingsValidationError
    data class InvalidFps(val value: Int) : RuntimeSettingsValidationError
    data class InvalidSegmentMinutes(val value: Int) : RuntimeSettingsValidationError
    data class InvalidMaxStorageGb(val value: Int) : RuntimeSettingsValidationError
    data class InvalidMinFreeStorageGb(val value: Int) : RuntimeSettingsValidationError
    data class InvalidPin(val value: String) : RuntimeSettingsValidationError
    data class InvalidToken(val value: String) : RuntimeSettingsValidationError
    data class InvalidTrustedDevice(val deviceId: String, val reason: String) : RuntimeSettingsValidationError
}
