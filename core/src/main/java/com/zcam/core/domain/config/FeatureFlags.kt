package com.zcam.core.domain.config

data class FeatureFlags(
    val mjpegStreaming: Boolean = true,
    val loopRecording: Boolean = true,
    val audioPushToTalk: Boolean = true,
    val audioLive: Boolean = true,
    val audioPlayback: Boolean = true,
    val trustedDevices: Boolean = true,
    val watchdogRecovery: Boolean = true
) {

    fun isEnabled(flag: FeatureFlag): Boolean = when (flag) {
        FeatureFlag.MJPEG_STREAMING -> mjpegStreaming
        FeatureFlag.LOOP_RECORDING -> loopRecording
        FeatureFlag.AUDIO_PUSH_TO_TALK -> audioPushToTalk
        FeatureFlag.AUDIO_LIVE -> audioLive
        FeatureFlag.AUDIO_PLAYBACK -> audioPlayback
        FeatureFlag.TRUSTED_DEVICES -> trustedDevices
        FeatureFlag.WATCHDOG_RECOVERY -> watchdogRecovery
    }

    fun withFlag(flag: FeatureFlag, enabled: Boolean): FeatureFlags = when (flag) {
        FeatureFlag.MJPEG_STREAMING -> copy(mjpegStreaming = enabled)
        FeatureFlag.LOOP_RECORDING -> copy(loopRecording = enabled)
        FeatureFlag.AUDIO_PUSH_TO_TALK -> copy(audioPushToTalk = enabled)
        FeatureFlag.AUDIO_LIVE -> copy(audioLive = enabled)
        FeatureFlag.AUDIO_PLAYBACK -> copy(audioPlayback = enabled)
        FeatureFlag.TRUSTED_DEVICES -> copy(trustedDevices = enabled)
        FeatureFlag.WATCHDOG_RECOVERY -> copy(watchdogRecovery = enabled)
    }
}
