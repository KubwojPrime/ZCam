package com.zcam.audio

data class AudioTransportConfig(
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val bytesPerSample: Int = 2,
    val frameDurationMs: Int = 20,
    val encoding: String = "pcm_s16le"
) {
    val frameBytes: Int
        get() = ((sampleRateHz * channelCount * bytesPerSample) * frameDurationMs) / 1000
}
