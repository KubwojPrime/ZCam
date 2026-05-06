package com.zcam.core.domain.config

data class TrustedDevice(
    val deviceId: String,
    val displayName: String,
    val addedAtEpochMillis: Long
)
