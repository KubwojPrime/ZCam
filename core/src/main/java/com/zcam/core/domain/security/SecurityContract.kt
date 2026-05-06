package com.zcam.core.domain.security

import com.zcam.core.domain.config.TrustedDevice

interface SecurityEngine {
    suspend fun validatePin(candidate: String): Boolean
    suspend fun validateToken(candidate: String): Boolean
    suspend fun isTrustedDevice(deviceId: String): Boolean
    suspend fun trustedDevices(): Set<TrustedDevice>
    suspend fun registerTrustedDevice(device: TrustedDevice)
    suspend fun revokeTrustedDevice(deviceId: String)
}

class AuthenticatePinUseCase(
    private val engine: SecurityEngine
) {
    suspend operator fun invoke(pin: String): Boolean = engine.validatePin(pin)
}

class AuthenticateTokenUseCase(
    private val engine: SecurityEngine
) {
    suspend operator fun invoke(token: String): Boolean = engine.validateToken(token)
}

class RegisterTrustedDeviceUseCase(
    private val engine: SecurityEngine
) {
    suspend operator fun invoke(device: TrustedDevice) {
        engine.registerTrustedDevice(device)
    }
}

class RevokeTrustedDeviceUseCase(
    private val engine: SecurityEngine
) {
    suspend operator fun invoke(deviceId: String) {
        engine.revokeTrustedDevice(deviceId)
    }
}

class IsTrustedDeviceUseCase(
    private val engine: SecurityEngine
) {
    suspend operator fun invoke(deviceId: String): Boolean = engine.isTrustedDevice(deviceId)
}
