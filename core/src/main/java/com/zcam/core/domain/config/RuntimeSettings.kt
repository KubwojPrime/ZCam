package com.zcam.core.domain.config

data class RuntimeSettings(
    val serverPort: Int = 8080,
    val stream: StreamConfig = StreamConfig(),
    val recording: LoopRecordingConfig = LoopRecordingConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val featureFlags: FeatureFlags = FeatureFlags()
)
