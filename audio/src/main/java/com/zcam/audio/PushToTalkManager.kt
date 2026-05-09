package com.zcam.audio

import com.zcam.core.domain.audio.AudioEngine

interface PushToTalkManager : AudioEngine {
    suspend fun isHealthy(): Boolean
    suspend fun handleLiveMode(mode: AudioLiveMode, enabled: Boolean): AudioCommandResult
    suspend fun playStoredAudio(request: AudioPlaybackRequest): AudioCommandResult
    suspend fun setVolume(levelPercent: Int): AudioCommandResult
    fun transportConfig(): AudioTransportConfig
    suspend fun registerLiveAudioSubscriber(subscriberId: String, onFrame: (ByteArray) -> Unit): Boolean
    suspend fun unregisterLiveAudioSubscriber(subscriberId: String)
    suspend fun openPushToTalkStream(streamId: String): Boolean
    suspend fun submitPushToTalkAudio(streamId: String, pcmFrame: ByteArray): Boolean
    suspend fun closePushToTalkStream(streamId: String)
    fun snapshotState(): AudioStateSnapshot
}
