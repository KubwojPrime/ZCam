package com.zcam.core.domain.config

data class SecurityConfig(
    val pinCode: String = "0000",
    val apiToken: String = "local-token",
    val trustedDevices: Set<TrustedDevice> = emptySet()
)
