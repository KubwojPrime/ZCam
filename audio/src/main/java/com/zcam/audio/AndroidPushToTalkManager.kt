package com.zcam.audio

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.audio.PlaybackSource
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPushToTalkManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger,
    private val systemVolumeController: SystemVolumeController,
    private val audioCuePlayer: AudioCuePlayer = NoOpAudioCuePlayer
) : PushToTalkManager {

    private val stateMutex = Mutex()
    private val snapshot = AtomicReference(
        AudioStateSnapshot(
            engineStarted = false,
            transmitting = false,
            liveListening = false,
            playingBack = false,
            activeClipId = null,
            volumePercent = DEFAULT_VOLUME_PERCENT,
            minVolumePercent = MIN_VOLUME_PERCENT,
            maxVolumePercent = MAX_VOLUME_PERCENT,
            aversiveCooldownMs = AVERSIVE_COOLDOWN_MS,
            aversiveCooldownRemainingMs = 0L
        )
    )

    private var engineStarted: Boolean = false
    private var transmitting: Boolean = false
    private var liveListening: Boolean = false
    private var playingBack: Boolean = false
    private var activeClipId: String? = null
    private var volumePercent: Int = DEFAULT_VOLUME_PERCENT
    private var lastAversivePlaybackAtEpochMs: Long = 0L
    private var playbackGeneration: Long = 0L
    private var playbackCompletionJob: Job? = null

    override suspend fun start() = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (engineStarted) return@withLock
            engineStarted = true
            transmitting = false
            liveListening = false
            playingBack = false
            activeClipId = null
            systemVolumeController.currentMusicVolumePercent()
                ?.coerceIn(MIN_VOLUME_PERCENT, MAX_VOLUME_PERCENT)
                ?.let { detectedVolume ->
                    volumePercent = detectedVolume
                }
            updateSnapshotLocked()
            logger.i(LogEventId.AUDIO_ENGINE_STARTED, "Audio engine started")
        }
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        audioCuePlayer.stop()
        stateMutex.withLock {
            engineStarted = false
            transmitting = false
            liveListening = false
            playingBack = false
            activeClipId = null
            playbackGeneration += 1L
            playbackCompletionJob?.cancel()
            playbackCompletionJob = null
            updateSnapshotLocked()
            logger.i(LogEventId.AUDIO_ENGINE_STOPPED, "Audio engine stopped")
        }
    }

    override suspend fun isHealthy(): Boolean = withContext(dispatchers.io) {
        snapshot.get().engineStarted
    }

    override suspend fun beginPushToTalk() {
        handleLiveMode(AudioLiveMode.PUSH_TO_TALK, enabled = true)
    }

    override suspend fun endPushToTalk() {
        handleLiveMode(AudioLiveMode.PUSH_TO_TALK, enabled = false)
    }

    override suspend fun beginLiveListen() {
        handleLiveMode(AudioLiveMode.LIVE_MONITOR, enabled = true)
    }

    override suspend fun stopLiveListen() {
        handleLiveMode(AudioLiveMode.LIVE_MONITOR, enabled = false)
    }

    override suspend fun startPlayback(source: PlaybackSource) {
        val clipId = when (source) {
            PlaybackSource.LIVE -> "legacy_live"
            PlaybackSource.ARCHIVE -> "legacy_archive"
        }
        playStoredAudio(
            AudioPlaybackRequest(
                clipId = clipId,
                category = AudioPlaybackCategory.STANDARD
            )
        )
    }

    override suspend fun stopPlayback() = withContext(dispatchers.io) {
        audioCuePlayer.stop()
        stateMutex.withLock {
            if (!engineStarted) return@withLock
            if (playingBack) {
                playingBack = false
                activeClipId = null
                playbackGeneration += 1L
                playbackCompletionJob?.cancel()
                playbackCompletionJob = null
                updateSnapshotLocked()
                logger.i(LogEventId.AUDIO_PLAYBACK_STOPPED, "Audio playback stopped")
            }
        }
    }

    override suspend fun handleLiveMode(mode: AudioLiveMode, enabled: Boolean): AudioCommandResult =
        withContext(dispatchers.io) {
            stateMutex.withLock {
                if (!engineStarted) {
                    return@withLock rejectLocked(
                        code = AudioCommandErrorCode.ENGINE_NOT_READY,
                        message = "audio engine not started"
                    )
                }

                when (mode) {
                    AudioLiveMode.PUSH_TO_TALK -> {
                        if (enabled && liveListening) {
                            return@withLock rejectLocked(
                                code = AudioCommandErrorCode.CONFLICT,
                                message = "cannot start push-to-talk while live monitoring is active"
                            )
                        }
                        transmitting = enabled
                    }
                    AudioLiveMode.LIVE_MONITOR -> {
                        if (enabled && transmitting) {
                            return@withLock rejectLocked(
                                code = AudioCommandErrorCode.CONFLICT,
                                message = "cannot start live monitoring while push-to-talk is active"
                            )
                        }
                        liveListening = enabled
                    }
                }

                val updated = updateSnapshotLocked()
                logger.i(
                    LogEventId.AUDIO_LIVE_MODE_CHANGED,
                    "Live mode ${mode.name.lowercase()} set to enabled=$enabled"
                )
                AudioCommandResult.Success(
                    state = updated,
                    message = "live mode updated"
                )
            }
        }

    override suspend fun playStoredAudio(request: AudioPlaybackRequest): AudioCommandResult =
        withContext(dispatchers.io) {
            stateMutex.withLock {
                if (!engineStarted) {
                    return@withLock rejectLocked(
                        code = AudioCommandErrorCode.ENGINE_NOT_READY,
                        message = "audio engine not started"
                    )
                }
                if (!CLIP_ID_REGEX.matches(request.clipId)) {
                    return@withLock rejectLocked(
                        code = AudioCommandErrorCode.INVALID_ARGUMENT,
                        message = "invalid clip id format"
                    )
                }

                val now = System.currentTimeMillis()
                if (request.category == AudioPlaybackCategory.AVERSIVE) {
                    val remainingMs = remainingAversiveCooldownLocked(now)
                    if (remainingMs > 0L) {
                        logger.w(
                            LogEventId.AUDIO_AVERSIVE_COOLDOWN_ACTIVE,
                            "Aversive playback rejected for ${request.clipId}, cooldown remaining ${remainingMs}ms"
                        )
                        return@withLock rejectLocked(
                            code = AudioCommandErrorCode.COOLDOWN_ACTIVE,
                            message = "aversive playback cooldown active for ${remainingMs}ms",
                            now = now
                        )
                    }
                    lastAversivePlaybackAtEpochMs = now
                }

                playbackGeneration += 1L
                playbackCompletionJob?.cancel()
                playbackCompletionJob = null
                audioCuePlayer.stop()
                playingBack = true
                activeClipId = request.clipId
                val updated = updateSnapshotLocked(now = now)
                val cuePlayback = audioCuePlayer.play(request.clipId, request.category)
                cuePlayback?.let { cue ->
                    schedulePlaybackCompletionLocked(
                        clipId = request.clipId,
                        generation = playbackGeneration,
                        delayMs = cue.durationMs
                    )
                }
                logger.i(
                    LogEventId.AUDIO_PLAYBACK_STARTED,
                    "Audio playback started clip=${request.clipId} category=${request.category.name.lowercase()}"
                )
                AudioCommandResult.Success(
                    state = updated,
                    message = "playback started"
                )
            }
        }

    override suspend fun setVolume(levelPercent: Int): AudioCommandResult = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (!engineStarted) {
                return@withLock rejectLocked(
                    code = AudioCommandErrorCode.ENGINE_NOT_READY,
                    message = "audio engine not started"
                )
            }
            if (levelPercent !in MIN_VOLUME_PERCENT..MAX_VOLUME_PERCENT) {
                return@withLock rejectLocked(
                    code = AudioCommandErrorCode.INVALID_ARGUMENT,
                    message = "volume must be between $MIN_VOLUME_PERCENT and $MAX_VOLUME_PERCENT"
                )
            }

            val systemVolumeResult = systemVolumeController.setMusicVolumePercent(levelPercent)
            if (!systemVolumeResult.applied) {
                return@withLock rejectLocked(
                    code = AudioCommandErrorCode.SYSTEM_VOLUME_UNAVAILABLE,
                    message = systemVolumeResult.reason ?: "system volume control unavailable"
                )
            }

            volumePercent = (systemVolumeResult.actualPercent ?: levelPercent)
                .coerceIn(MIN_VOLUME_PERCENT, MAX_VOLUME_PERCENT)
            val updated = updateSnapshotLocked()
            logger.i(
                LogEventId.AUDIO_VOLUME_UPDATED,
                "Audio volume set to $volumePercent% (requested=$levelPercent stream=${systemVolumeResult.streamVolume}/${systemVolumeResult.streamMaxVolume})"
            )
            AudioCommandResult.Success(
                state = updated,
                message = "volume updated"
            )
        }
    }

    override fun snapshotState(): AudioStateSnapshot = snapshot.get()

    private fun rejectLocked(
        code: AudioCommandErrorCode,
        message: String,
        now: Long = System.currentTimeMillis()
    ): AudioCommandResult.Failure {
        val current = updateSnapshotLocked(now = now)
        return AudioCommandResult.Failure(
            code = code,
            state = current,
            message = message
        )
    }

    private fun updateSnapshotLocked(now: Long = System.currentTimeMillis()): AudioStateSnapshot {
        val updated = AudioStateSnapshot(
            engineStarted = engineStarted,
            transmitting = transmitting,
            liveListening = liveListening,
            playingBack = playingBack,
            activeClipId = activeClipId,
            volumePercent = volumePercent,
            minVolumePercent = MIN_VOLUME_PERCENT,
            maxVolumePercent = MAX_VOLUME_PERCENT,
            aversiveCooldownMs = AVERSIVE_COOLDOWN_MS,
            aversiveCooldownRemainingMs = remainingAversiveCooldownLocked(now)
        )
        snapshot.set(updated)
        return updated
    }

    private fun remainingAversiveCooldownLocked(now: Long): Long {
        if (lastAversivePlaybackAtEpochMs <= 0L) return 0L
        val elapsed = (now - lastAversivePlaybackAtEpochMs).coerceAtLeast(0L)
        return (AVERSIVE_COOLDOWN_MS - elapsed).coerceAtLeast(0L)
    }

    private fun schedulePlaybackCompletionLocked(
        clipId: String,
        generation: Long,
        delayMs: Long
    ) {
        if (delayMs <= 0L) return
        playbackCompletionJob = CoroutineScope(dispatchers.default).launch {
            delay(delayMs)
            stateMutex.withLock {
                if (!engineStarted) return@withLock
                if (playbackGeneration != generation) return@withLock
                if (!playingBack || activeClipId != clipId) return@withLock
                playingBack = false
                activeClipId = null
                playbackCompletionJob = null
                updateSnapshotLocked()
                logger.i(
                    LogEventId.AUDIO_PLAYBACK_STOPPED,
                    "Audio playback finished clip=$clipId"
                )
            }
        }
    }

    private companion object {
        val CLIP_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")

        const val MIN_VOLUME_PERCENT = 0
        const val MAX_VOLUME_PERCENT = 85
        const val DEFAULT_VOLUME_PERCENT = 40
        const val AVERSIVE_COOLDOWN_MS = 10_000L
    }
}
