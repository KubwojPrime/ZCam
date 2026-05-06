package com.zcam.core.domain.audio

enum class PlaybackSource {
    LIVE,
    ARCHIVE
}

interface AudioEngine {
    suspend fun start()
    suspend fun stop()
    suspend fun beginPushToTalk()
    suspend fun endPushToTalk()
    suspend fun beginLiveListen()
    suspend fun stopLiveListen()
    suspend fun startPlayback(source: PlaybackSource)
    suspend fun stopPlayback()
}

class BeginPushToTalkUseCase(
    private val engine: AudioEngine
) {
    suspend operator fun invoke() {
        engine.beginPushToTalk()
    }
}

class EndPushToTalkUseCase(
    private val engine: AudioEngine
) {
    suspend operator fun invoke() {
        engine.endPushToTalk()
    }
}

class StartAudioPlaybackUseCase(
    private val engine: AudioEngine
) {
    suspend operator fun invoke(source: PlaybackSource) {
        engine.startPlayback(source)
    }
}

class StopAudioPlaybackUseCase(
    private val engine: AudioEngine
) {
    suspend operator fun invoke() {
        engine.stopPlayback()
    }
}

class StartLiveAudioUseCase(
    private val engine: AudioEngine
) {
    suspend operator fun invoke() {
        engine.beginLiveListen()
    }
}

class StopLiveAudioUseCase(
    private val engine: AudioEngine
) {
    suspend operator fun invoke() {
        engine.stopLiveListen()
    }
}
