package com.zcam.core.domain.config

object RuntimeSettingsDefaults {
    val value: RuntimeSettings = RuntimeSettings(
        serverPort = 8080,
        stream = StreamConfig(
            resolution = VideoResolution.HD_720P,
            fps = 15,
            codec = VideoCodec.H264
        ),
        recording = LoopRecordingConfig(
            segmentMinutes = 5,
            maxStorageGb = 32,
            minFreeStorageGb = 5
        ),
        security = SecurityConfig(
            pinCode = "0000",
            apiToken = "local-token"
        ),
        featureFlags = FeatureFlags()
    )
}
