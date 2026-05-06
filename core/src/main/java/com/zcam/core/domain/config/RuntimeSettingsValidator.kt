package com.zcam.core.domain.config

object RuntimeSettingsValidator {

    fun validate(settings: RuntimeSettings): List<RuntimeSettingsValidationError> {
        val errors = mutableListOf<RuntimeSettingsValidationError>()

        if (settings.serverPort !in 1024..65535) {
            errors += RuntimeSettingsValidationError.InvalidServerPort(settings.serverPort)
        }
        if (settings.stream.fps !in 1..60) {
            errors += RuntimeSettingsValidationError.InvalidFps(settings.stream.fps)
        }
        if (settings.recording.segmentMinutes !in 1..60) {
            errors += RuntimeSettingsValidationError.InvalidSegmentMinutes(settings.recording.segmentMinutes)
        }
        if (settings.recording.maxStorageGb !in 1..512) {
            errors += RuntimeSettingsValidationError.InvalidMaxStorageGb(settings.recording.maxStorageGb)
        }
        if (settings.recording.minFreeStorageGb !in 1..256) {
            errors += RuntimeSettingsValidationError.InvalidMinFreeStorageGb(settings.recording.minFreeStorageGb)
        }
        if (settings.recording.minFreeStorageGb >= settings.recording.maxStorageGb) {
            errors += RuntimeSettingsValidationError.InvalidMinFreeStorageGb(settings.recording.minFreeStorageGb)
        }

        val pin = settings.security.pinCode
        if (!PIN_REGEX.matches(pin)) {
            errors += RuntimeSettingsValidationError.InvalidPin(pin)
        }

        val token = settings.security.apiToken
        if (!TOKEN_REGEX.matches(token)) {
            errors += RuntimeSettingsValidationError.InvalidToken(token)
        }

        settings.security.trustedDevices.forEach { device ->
            when {
                device.deviceId.isBlank() -> {
                    errors += RuntimeSettingsValidationError.InvalidTrustedDevice(device.deviceId, "device id is blank")
                }
                !DEVICE_ID_REGEX.matches(device.deviceId) -> {
                    errors += RuntimeSettingsValidationError.InvalidTrustedDevice(device.deviceId, "device id has invalid characters")
                }
                device.displayName.isBlank() -> {
                    errors += RuntimeSettingsValidationError.InvalidTrustedDevice(device.deviceId, "display name is blank")
                }
                device.addedAtEpochMillis <= 0L -> {
                    errors += RuntimeSettingsValidationError.InvalidTrustedDevice(device.deviceId, "timestamp must be positive")
                }
            }
        }

        return errors
    }

    fun requireValid(settings: RuntimeSettings) {
        val errors = validate(settings)
        require(errors.isEmpty()) {
            "Runtime settings are invalid: ${errors.joinToString()}"
        }
    }

    private val PIN_REGEX = Regex("^[0-9]{4,10}$")
    private val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val DEVICE_ID_REGEX = Regex("^[A-Za-z0-9._:-]{3,64}$")
}
