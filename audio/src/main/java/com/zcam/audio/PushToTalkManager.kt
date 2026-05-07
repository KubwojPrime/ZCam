package com.zcam.audio

import com.zcam.core.domain.audio.AudioEngine

interface PushToTalkManager : AudioEngine {
    suspend fun isHealthy(): Boolean
    suspend fun handleLiveMode(mode: AudioLiveMode, enabled: Boolean): AudioCommandResult
    suspend fun playStoredAudio(request: AudioPlaybackRequest): AudioCommandResult
    suspend fun setVolume(levelPercent: Int): AudioCommandResult
    fun snapshotState(): AudioStateSnapshot
}
