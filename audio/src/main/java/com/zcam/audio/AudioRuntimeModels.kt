package com.zcam.audio

enum class AudioLiveMode {
    PUSH_TO_TALK,
    LIVE_MONITOR
}

enum class AudioPlaybackCategory {
    STANDARD,
    AVERSIVE
}

data class AudioPlaybackRequest(
    val clipId: String,
    val category: AudioPlaybackCategory = AudioPlaybackCategory.STANDARD
)

data class AudioStateSnapshot(
    val engineStarted: Boolean,
    val transmitting: Boolean,
    val liveListening: Boolean,
    val playingBack: Boolean,
    val activeClipId: String?,
    val volumePercent: Int,
    val minVolumePercent: Int,
    val maxVolumePercent: Int,
    val aversiveCooldownMs: Long,
    val aversiveCooldownRemainingMs: Long
)

enum class AudioCommandErrorCode {
    ENGINE_NOT_READY,
    INVALID_ARGUMENT,
    COOLDOWN_ACTIVE,
    CONFLICT,
    SYSTEM_VOLUME_UNAVAILABLE
}

sealed interface AudioCommandResult {
    data class Success(
        val state: AudioStateSnapshot,
        val message: String
    ) : AudioCommandResult

    data class Failure(
        val code: AudioCommandErrorCode,
        val state: AudioStateSnapshot,
        val message: String
    ) : AudioCommandResult
}
