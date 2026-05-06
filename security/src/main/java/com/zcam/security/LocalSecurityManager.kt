package com.zcam.security

import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalSecurityManager @Inject constructor(
    private val runtimeSettingsRepository: RuntimeSettingsRepository
) : SecurityManager {

    override suspend fun validateToken(candidate: String): Boolean {
        val settings = runtimeSettingsRepository.settings.first()
        return settings.security.apiToken == candidate
    }

    override suspend fun validatePin(candidate: String): Boolean {
        val settings = runtimeSettingsRepository.settings.first()
        return settings.security.pinCode == candidate
    }

    override suspend fun isTrustedDevice(deviceId: String): Boolean {
        val settings = runtimeSettingsRepository.settings.first()
        if (!settings.featureFlags.trustedDevices) {
            return true
        }
        return settings.security.trustedDevices.any { it.deviceId == deviceId }
    }

    override suspend fun trustedDevices(): Set<TrustedDevice> {
        return runtimeSettingsRepository.settings.first().security.trustedDevices
    }

    override suspend fun registerTrustedDevice(device: TrustedDevice) {
        when (val result = runtimeSettingsRepository.upsertTrustedDevice(device)) {
            is RuntimeSettingsUpdateResult.Success -> Unit
            is RuntimeSettingsUpdateResult.Forbidden -> {
                throw IllegalStateException("Register trusted device forbidden: ${result.reason}")
            }
            is RuntimeSettingsUpdateResult.ValidationFailed -> {
                throw IllegalArgumentException(
                    "Register trusted device failed validation: ${result.errors.joinToString()}"
                )
            }
        }
    }

    override suspend fun revokeTrustedDevice(deviceId: String) {
        when (val result = runtimeSettingsRepository.removeTrustedDevice(deviceId)) {
            is RuntimeSettingsUpdateResult.Success -> Unit
            is RuntimeSettingsUpdateResult.Forbidden -> {
                throw IllegalStateException("Revoke trusted device forbidden: ${result.reason}")
            }
            is RuntimeSettingsUpdateResult.ValidationFailed -> {
                throw IllegalArgumentException(
                    "Revoke trusted device failed validation: ${result.errors.joinToString()}"
                )
            }
        }
    }
}
