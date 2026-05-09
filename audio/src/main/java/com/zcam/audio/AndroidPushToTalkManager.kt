package com.zcam.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.audio.PlaybackSource
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class AndroidPushToTalkManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger,
    private val systemVolumeController: SystemVolumeController,
    private val audioCuePlayer: AudioCuePlayer = NoOpAudioCuePlayer
) : PushToTalkManager {

    private val stateMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val transportConfig = AudioTransportConfig()
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

    private val liveSubscribers = LinkedHashMap<String, (ByteArray) -> Unit>()
    private var liveCaptureJob: Job? = null
    private var liveCaptureGeneration: Long = 0L

    private var activePushToTalkStreamId: String? = null
    private var pushToTalkPlaybackJob: Job? = null
    private var pushToTalkPlaybackGeneration: Long = 0L
    private var pushToTalkPlaybackQueue: Channel<ByteArray>? = null

    override suspend fun start() = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (engineStarted) return@withLock
            engineStarted = true
            transmitting = false
            liveListening = false
            playingBack = false
            activeClipId = null
            activePushToTalkStreamId = null
            stopLiveCaptureLocked()
            stopPushToTalkPlaybackLocked()
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
            activePushToTalkStreamId = null
            liveSubscribers.clear()
            stopLiveCaptureLocked()
            stopPushToTalkPlaybackLocked()
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
                        if (!enabled) {
                            activePushToTalkStreamId = null
                            stopPushToTalkPlaybackLocked()
                        }
                    }
                    AudioLiveMode.LIVE_MONITOR -> {
                        if (enabled && transmitting) {
                            return@withLock rejectLocked(
                                code = AudioCommandErrorCode.CONFLICT,
                                message = "cannot start live monitoring while push-to-talk is active"
                            )
                        }
                        liveListening = enabled
                        if (enabled) {
                            ensureLiveCaptureLocked()
                        } else {
                            stopLiveCaptureLocked()
                        }
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

    override fun transportConfig(): AudioTransportConfig = transportConfig

    override suspend fun registerLiveAudioSubscriber(
        subscriberId: String,
        onFrame: (ByteArray) -> Unit
    ): Boolean = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (!engineStarted || !liveListening || subscriberId.isBlank()) {
                return@withLock false
            }
            liveSubscribers[subscriberId] = onFrame
            ensureLiveCaptureLocked()
            true
        }
    }

    override suspend fun unregisterLiveAudioSubscriber(subscriberId: String) = withContext(dispatchers.io) {
        stateMutex.withLock {
            liveSubscribers.remove(subscriberId)
            if (liveSubscribers.isEmpty()) {
                stopLiveCaptureLocked()
            }
        }
    }

    override suspend fun openPushToTalkStream(streamId: String): Boolean = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (!engineStarted || !transmitting || streamId.isBlank()) {
                return@withLock false
            }
            if (activePushToTalkStreamId != null && activePushToTalkStreamId != streamId) {
                return@withLock false
            }
            activePushToTalkStreamId = streamId
            ensurePushToTalkPlaybackLocked()
            true
        }
    }

    override suspend fun submitPushToTalkAudio(streamId: String, pcmFrame: ByteArray): Boolean =
        withContext(dispatchers.io) {
            val queue = stateMutex.withLock {
                if (!engineStarted || !transmitting || activePushToTalkStreamId != streamId) {
                    return@withLock null
                }
                pushToTalkPlaybackQueue
            } ?: return@withContext false

            if (pcmFrame.isEmpty()) return@withContext true
            queue.trySend(pcmFrame.copyOf()).isSuccess
        }

    override suspend fun closePushToTalkStream(streamId: String) = withContext(dispatchers.io) {
        stateMutex.withLock {
            if (activePushToTalkStreamId == streamId) {
                activePushToTalkStreamId = null
                stopPushToTalkPlaybackLocked()
            }
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

    private fun ensureLiveCaptureLocked() {
        if (!engineStarted || !liveListening || liveSubscribers.isEmpty()) return
        if (liveCaptureJob != null) return
        liveCaptureGeneration += 1L
        val generation = liveCaptureGeneration
        liveCaptureJob = runtimeScope.launch {
            captureLiveAudioLoop(generation)
        }
    }

    private fun stopLiveCaptureLocked() {
        liveCaptureGeneration += 1L
        liveCaptureJob?.cancel()
        liveCaptureJob = null
    }

    private suspend fun captureLiveAudioLoop(generation: Long) {
        val record = createAudioRecord() ?: return
        val frameBuffer = ByteArray(transportConfig.frameBytes)
        try {
            record.startRecording()
            while (coroutineContext.isActive) {
                val shouldContinue = stateMutex.withLock {
                    engineStarted && liveListening && liveSubscribers.isNotEmpty() && liveCaptureGeneration == generation
                }
                if (!shouldContinue) break

                val read = record.read(frameBuffer, 0, frameBuffer.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) {
                    if (read < 0) {
                        logger.w(
                            LogEventId.COMPONENT_FAILED,
                            "Live audio capture read failed code=$read"
                        )
                    }
                    delay(10L)
                    continue
                }

                val payload = frameBuffer.copyOf(read)
                val sinks = stateMutex.withLock { liveSubscribers.toMap() }
                if (sinks.isEmpty()) break

                val failed = ArrayList<String>(2)
                sinks.forEach { (subscriberId, sink) ->
                    runCatching { sink(payload) }
                        .onFailure { failed.add(subscriberId) }
                }
                if (failed.isNotEmpty()) {
                    stateMutex.withLock {
                        failed.forEach(liveSubscribers::remove)
                    }
                }
            }
        } catch (error: Exception) {
            logger.w(
                LogEventId.COMPONENT_FAILED,
                "Live audio capture stopped: ${error.message}"
            )
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            stateMutex.withLock {
                if (liveCaptureGeneration == generation) {
                    liveCaptureJob = null
                }
            }
        }
    }

    private fun ensurePushToTalkPlaybackLocked() {
        if (!engineStarted || !transmitting || activePushToTalkStreamId.isNullOrBlank()) return
        if (pushToTalkPlaybackJob != null && pushToTalkPlaybackQueue != null) return

        val queue = Channel<ByteArray>(
            capacity = PUSH_TO_TALK_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        pushToTalkPlaybackQueue = queue
        pushToTalkPlaybackGeneration += 1L
        val generation = pushToTalkPlaybackGeneration
        pushToTalkPlaybackJob = runtimeScope.launch {
            playbackPushToTalkLoop(queue, generation)
        }
    }

    private fun stopPushToTalkPlaybackLocked() {
        pushToTalkPlaybackGeneration += 1L
        pushToTalkPlaybackQueue?.close()
        pushToTalkPlaybackQueue = null
        pushToTalkPlaybackJob?.cancel()
        pushToTalkPlaybackJob = null
    }

    private suspend fun playbackPushToTalkLoop(queue: Channel<ByteArray>, generation: Long) {
        val track = createAudioTrack() ?: return
        try {
            track.play()
            for (frame in queue) {
                val canWrite = stateMutex.withLock {
                    engineStarted &&
                        transmitting &&
                        activePushToTalkStreamId != null &&
                        pushToTalkPlaybackGeneration == generation
                }
                if (!canWrite) break
                writeFully(track, frame)
            }
        } catch (error: Exception) {
            logger.w(
                LogEventId.COMPONENT_FAILED,
                "Push-to-talk playback stopped: ${error.message}"
            )
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
            stateMutex.withLock {
                if (pushToTalkPlaybackGeneration == generation) {
                    pushToTalkPlaybackQueue = null
                    pushToTalkPlaybackJob = null
                }
            }
        }
    }

    private fun createAudioRecord(): AudioRecord? {
        return runCatching {
            val minBufferSize = AudioRecord.getMinBufferSize(
                transportConfig.sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) return null

            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(transportConfig.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferSize, transportConfig.frameBytes * 6))
                .build()
                .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                ?: return null
        }.getOrElse { error ->
            logger.w(
                LogEventId.COMPONENT_FAILED,
                "AudioRecord init failed: ${error.message}"
            )
            null
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        return runCatching {
            val minBufferSize = AudioTrack.getMinBufferSize(
                transportConfig.sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) return null

            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(transportConfig.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBufferSize, transportConfig.frameBytes * 8))
                .build()
                .takeIf { it.state == AudioTrack.STATE_INITIALIZED }
                ?: return null
        }.getOrElse { error ->
            logger.w(
                LogEventId.COMPONENT_FAILED,
                "AudioTrack init failed: ${error.message}"
            )
            null
        }
    }

    private fun writeFully(track: AudioTrack, frame: ByteArray) {
        var offset = 0
        while (offset < frame.size) {
            val written = track.write(frame, offset, frame.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    private companion object {
        val CLIP_ID_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")

        const val MIN_VOLUME_PERCENT = 0
        const val MAX_VOLUME_PERCENT = 85
        const val DEFAULT_VOLUME_PERCENT = 40
        const val AVERSIVE_COOLDOWN_MS = 10_000L
        const val PUSH_TO_TALK_QUEUE_CAPACITY = 48
    }
}
