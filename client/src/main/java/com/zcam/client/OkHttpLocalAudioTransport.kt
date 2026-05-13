package com.zcam.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.zcam.audio.AudioTransportConfig
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.w
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OkHttpLocalAudioTransport @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : LocalAudioTransport {

    private val client = OkHttpClient.Builder()
        .connectTimeout(SOCKET_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(AUDIO_SOCKET_PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val stateMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val transportConfig = AudioTransportConfig()

    private var liveSocket: WebSocket? = null
    private var livePlaybackJob: Job? = null
    private var livePlaybackQueue: Channel<ByteArray>? = null
    private var liveGeneration: Long = 0L
    private var liveReconnectJob: Job? = null
    private var desiredLiveTarget: ClientTarget? = null
    private var liveSessionId: String? = null
    private var liveReconnectAttempts: Int = 0

    private var pushToTalkSocket: WebSocket? = null
    private var pushToTalkCaptureJob: Job? = null
    private var pushToTalkGeneration: Long = 0L
    private var pushToTalkReconnectJob: Job? = null
    private var desiredPushToTalkTarget: ClientTarget? = null
    private var pushToTalkSessionId: String? = null
    private var pushToTalkReconnectAttempts: Int = 0

    override suspend fun startLiveListen(target: ClientTarget): LocalAudioTransportResult =
        withContext(dispatchers.io) {
            stopLiveListen()

            val sessionId = newAudioSessionId("live")
            val generation = stateMutex.withLock {
                desiredLiveTarget = target
                liveSessionId = sessionId
                liveReconnectAttempts = 0
                liveReconnectJob?.cancel()
                liveReconnectJob = null
                liveGeneration += 1L
                liveGeneration
            }

            openLiveSocket(
                target = target,
                sessionId = sessionId,
                generation = generation,
                awaitReady = true,
                retryOnInitialFailure = false
            )
        }

    override suspend fun stopLiveListen() = withContext(dispatchers.io) {
        stateMutex.withLock {
            stopLiveLocked(
                closeSocket = true,
                clearDesiredState = true,
                advanceGeneration = true
            )
        }
    }

    override suspend fun startPushToTalk(target: ClientTarget): LocalAudioTransportResult =
        withContext(dispatchers.io) {
            stopPushToTalk()

            val sessionId = newAudioSessionId("ptt")
            val generation = stateMutex.withLock {
                desiredPushToTalkTarget = target
                pushToTalkSessionId = sessionId
                pushToTalkReconnectAttempts = 0
                pushToTalkReconnectJob?.cancel()
                pushToTalkReconnectJob = null
                pushToTalkGeneration += 1L
                pushToTalkGeneration
            }

            openPushToTalkSocket(
                target = target,
                sessionId = sessionId,
                generation = generation,
                awaitReady = true,
                retryOnInitialFailure = false
            )
        }

    override suspend fun stopPushToTalk() = withContext(dispatchers.io) {
        stateMutex.withLock {
            stopPushToTalkLocked(
                closeSocket = true,
                clearDesiredState = true,
                advanceGeneration = true
            )
        }
    }

    override suspend fun stopAll() = withContext(dispatchers.io) {
        stateMutex.withLock {
            stopLiveLocked(
                closeSocket = true,
                clearDesiredState = true,
                advanceGeneration = true
            )
            stopPushToTalkLocked(
                closeSocket = true,
                clearDesiredState = true,
                advanceGeneration = true
            )
        }
    }

    private suspend fun openLiveSocket(
        target: ClientTarget,
        sessionId: String,
        generation: Long,
        awaitReady: Boolean,
        retryOnInitialFailure: Boolean
    ): LocalAudioTransportResult {
        val ready = CompletableDeferred<LocalAudioTransportResult>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runtimeScope.launch {
                    val started = stateMutex.withLock {
                        if (liveGeneration != generation || liveSessionId != sessionId || desiredLiveTarget != target) {
                            false
                        } else {
                            liveReconnectAttempts = 0
                            startLivePlaybackLocked(generation, webSocket)
                        }
                    }
                    if (started) {
                        if (!ready.isCompleted) {
                            ready.complete(LocalAudioTransportResult.Success)
                        }
                    } else {
                        runCatching { webSocket.close(1011, "live_playback_unavailable") }
                        if (!ready.isCompleted) {
                            ready.complete(LocalAudioTransportResult.Failure("live_playback_unavailable"))
                        }
                        if (retryOnInitialFailure) {
                            handleLiveSocketDisconnect(
                                generation = generation,
                                target = target,
                                sessionId = sessionId,
                                reason = "live_playback_unavailable"
                            )
                        } else {
                            stateMutex.withLock {
                                if (liveGeneration == generation && liveSessionId == sessionId) {
                                    stopLiveLocked(
                                        closeSocket = false,
                                        clearDesiredState = true,
                                        advanceGeneration = true
                                    )
                                }
                            }
                        }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runtimeScope.launch {
                    val queue = stateMutex.withLock {
                        if (liveGeneration == generation && liveSessionId == sessionId) {
                            livePlaybackQueue
                        } else {
                            null
                        }
                    } ?: return@launch
                    queue.trySend(bytes.toByteArray())
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runtimeScope.launch {
                    if (!ready.isCompleted) {
                        ready.complete(
                            LocalAudioTransportResult.Failure(
                                reason = "live_socket_closed",
                                detail = reason.ifBlank { code.toString() }
                            )
                        )
                        if (retryOnInitialFailure) {
                            handleLiveSocketDisconnect(
                                generation = generation,
                                target = target,
                                sessionId = sessionId,
                                reason = "live_socket_closed"
                            )
                        } else {
                            stateMutex.withLock {
                                if (liveGeneration == generation && liveSessionId == sessionId) {
                                    stopLiveLocked(
                                        closeSocket = false,
                                        clearDesiredState = true,
                                        advanceGeneration = true
                                    )
                                }
                            }
                        }
                    } else {
                        handleLiveSocketDisconnect(
                            generation = generation,
                            target = target,
                            sessionId = sessionId,
                            reason = "live_socket_closed"
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runtimeScope.launch {
                    if (!ready.isCompleted) {
                        ready.complete(
                            LocalAudioTransportResult.Failure(
                                reason = "live_socket_failed",
                                detail = t.message
                            )
                        )
                        if (retryOnInitialFailure) {
                            handleLiveSocketDisconnect(
                                generation = generation,
                                target = target,
                                sessionId = sessionId,
                                reason = "live_socket_failed"
                            )
                        } else {
                            stateMutex.withLock {
                                if (liveGeneration == generation && liveSessionId == sessionId) {
                                    stopLiveLocked(
                                        closeSocket = false,
                                        clearDesiredState = true,
                                        advanceGeneration = true
                                    )
                                }
                            }
                        }
                    } else {
                        handleLiveSocketDisconnect(
                            generation = generation,
                            target = target,
                            sessionId = sessionId,
                            reason = "live_socket_failed"
                        )
                    }
                }
            }
        }

        client.newWebSocket(buildSocketRequest(target, "live", sessionId), listener)
        if (!awaitReady) return LocalAudioTransportResult.Success
        return withTimeoutOrNull(SOCKET_OPEN_TIMEOUT_MS) {
            ready.await()
        } ?: LocalAudioTransportResult.Failure("live_socket_timeout")
    }

    private suspend fun openPushToTalkSocket(
        target: ClientTarget,
        sessionId: String,
        generation: Long,
        awaitReady: Boolean,
        retryOnInitialFailure: Boolean
    ): LocalAudioTransportResult {
        val ready = CompletableDeferred<LocalAudioTransportResult>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runtimeScope.launch {
                    val started = stateMutex.withLock {
                        if (
                            pushToTalkGeneration != generation ||
                            pushToTalkSessionId != sessionId ||
                            desiredPushToTalkTarget != target
                        ) {
                            false
                        } else {
                            pushToTalkReconnectAttempts = 0
                            startPushToTalkCaptureLocked(generation, webSocket)
                        }
                    }
                    if (started) {
                        if (!ready.isCompleted) {
                            ready.complete(LocalAudioTransportResult.Success)
                        }
                    } else {
                        runCatching { webSocket.close(1011, "ptt_capture_unavailable") }
                        if (!ready.isCompleted) {
                            ready.complete(LocalAudioTransportResult.Failure("ptt_capture_unavailable"))
                        }
                        if (retryOnInitialFailure) {
                            handlePushToTalkSocketDisconnect(
                                generation = generation,
                                target = target,
                                sessionId = sessionId,
                                reason = "ptt_capture_unavailable"
                            )
                        } else {
                            stateMutex.withLock {
                                if (pushToTalkGeneration == generation && pushToTalkSessionId == sessionId) {
                                    stopPushToTalkLocked(
                                        closeSocket = false,
                                        clearDesiredState = true,
                                        advanceGeneration = true
                                    )
                                }
                            }
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runtimeScope.launch {
                    if (!ready.isCompleted) {
                        ready.complete(
                            LocalAudioTransportResult.Failure(
                                reason = "ptt_socket_closed",
                                detail = reason.ifBlank { code.toString() }
                            )
                        )
                        if (retryOnInitialFailure) {
                            handlePushToTalkSocketDisconnect(
                                generation = generation,
                                target = target,
                                sessionId = sessionId,
                                reason = "ptt_socket_closed"
                            )
                        } else {
                            stateMutex.withLock {
                                if (pushToTalkGeneration == generation && pushToTalkSessionId == sessionId) {
                                    stopPushToTalkLocked(
                                        closeSocket = false,
                                        clearDesiredState = true,
                                        advanceGeneration = true
                                    )
                                }
                            }
                        }
                    } else {
                        handlePushToTalkSocketDisconnect(
                            generation = generation,
                            target = target,
                            sessionId = sessionId,
                            reason = "ptt_socket_closed"
                        )
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runtimeScope.launch {
                    if (!ready.isCompleted) {
                        ready.complete(
                            LocalAudioTransportResult.Failure(
                                reason = "ptt_socket_failed",
                                detail = t.message
                            )
                        )
                        if (retryOnInitialFailure) {
                            handlePushToTalkSocketDisconnect(
                                generation = generation,
                                target = target,
                                sessionId = sessionId,
                                reason = "ptt_socket_failed"
                            )
                        } else {
                            stateMutex.withLock {
                                if (pushToTalkGeneration == generation && pushToTalkSessionId == sessionId) {
                                    stopPushToTalkLocked(
                                        closeSocket = false,
                                        clearDesiredState = true,
                                        advanceGeneration = true
                                    )
                                }
                            }
                        }
                    } else {
                        handlePushToTalkSocketDisconnect(
                            generation = generation,
                            target = target,
                            sessionId = sessionId,
                            reason = "ptt_socket_failed"
                        )
                    }
                }
            }
        }

        client.newWebSocket(buildSocketRequest(target, "ptt", sessionId), listener)
        if (!awaitReady) return LocalAudioTransportResult.Success
        return withTimeoutOrNull(SOCKET_OPEN_TIMEOUT_MS) {
            ready.await()
        } ?: LocalAudioTransportResult.Failure("ptt_socket_timeout")
    }

    private suspend fun handleLiveSocketDisconnect(
        generation: Long,
        target: ClientTarget,
        sessionId: String,
        reason: String
    ) {
        var attempt = 0
        stateMutex.withLock {
            if (liveGeneration != generation || liveSessionId != sessionId || desiredLiveTarget != target) {
                return
            }
            stopLiveLocked(
                closeSocket = false,
                clearDesiredState = false,
                advanceGeneration = true
            )
            liveReconnectAttempts += 1
            attempt = liveReconnectAttempts
        }
        scheduleLiveReconnect(target, sessionId, attempt, reason)
    }

    private suspend fun handlePushToTalkSocketDisconnect(
        generation: Long,
        target: ClientTarget,
        sessionId: String,
        reason: String
    ) {
        var attempt = 0
        stateMutex.withLock {
            if (
                pushToTalkGeneration != generation ||
                pushToTalkSessionId != sessionId ||
                desiredPushToTalkTarget != target
            ) {
                return
            }
            stopPushToTalkLocked(
                closeSocket = false,
                clearDesiredState = false,
                advanceGeneration = true
            )
            pushToTalkReconnectAttempts += 1
            attempt = pushToTalkReconnectAttempts
        }
        schedulePushToTalkReconnect(target, sessionId, attempt, reason)
    }

    private fun scheduleLiveReconnect(
        target: ClientTarget,
        sessionId: String,
        attempt: Int,
        reason: String
    ) {
        val delayMs = reconnectDelayMs(attempt)
        logger.w("Client live audio disconnected ($reason); reconnect in ${delayMs}ms")
        liveReconnectJob?.cancel()
        liveReconnectJob = runtimeScope.launch {
            delay(delayMs)
            val generation = stateMutex.withLock {
                if (desiredLiveTarget != target || liveSessionId != sessionId) {
                    return@withLock null
                }
                liveReconnectJob = null
                liveGeneration += 1L
                liveGeneration
            } ?: return@launch
            openLiveSocket(
                target = target,
                sessionId = sessionId,
                generation = generation,
                awaitReady = false,
                retryOnInitialFailure = true
            )
        }
    }

    private fun schedulePushToTalkReconnect(
        target: ClientTarget,
        sessionId: String,
        attempt: Int,
        reason: String
    ) {
        val delayMs = reconnectDelayMs(attempt)
        logger.w("Client push-to-talk disconnected ($reason); reconnect in ${delayMs}ms")
        pushToTalkReconnectJob?.cancel()
        pushToTalkReconnectJob = runtimeScope.launch {
            delay(delayMs)
            val generation = stateMutex.withLock {
                if (desiredPushToTalkTarget != target || pushToTalkSessionId != sessionId) {
                    return@withLock null
                }
                pushToTalkReconnectJob = null
                pushToTalkGeneration += 1L
                pushToTalkGeneration
            } ?: return@launch
            openPushToTalkSocket(
                target = target,
                sessionId = sessionId,
                generation = generation,
                awaitReady = false,
                retryOnInitialFailure = true
            )
        }
    }

    private fun startLivePlaybackLocked(generation: Long, webSocket: WebSocket): Boolean {
        val track = createAudioTrack() ?: return false
        val queue = Channel<ByteArray>(
            capacity = LIVE_PLAYBACK_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        liveSocket = webSocket
        livePlaybackQueue = queue
        livePlaybackJob = runtimeScope.launch {
            try {
                track.play()
                for (frame in queue) {
                    val stillActive = stateMutex.withLock {
                        liveGeneration == generation && liveSessionId != null
                    }
                    if (!stillActive) break
                    if (!writeFully(track, frame)) {
                        throw IllegalStateException("live audio track write failed")
                    }
                }
            } catch (error: Exception) {
                logger.w("Client live playback failed: ${error.message}")
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.stop() }
                runCatching { track.release() }
                stateMutex.withLock {
                    if (liveGeneration == generation) {
                        livePlaybackQueue = null
                        livePlaybackJob = null
                        liveSocket = null
                    }
                }
                runCatching { webSocket.close(1000, "live_playback_stopped") }
            }
        }
        return true
    }

    private fun startPushToTalkCaptureLocked(generation: Long, webSocket: WebSocket): Boolean {
        val record = createAudioRecord() ?: return false
        pushToTalkSocket = webSocket
        pushToTalkCaptureJob = runtimeScope.launch {
            val frameBuffer = ByteArray(transportConfig.frameBytes)
            try {
                record.startRecording()
                while (true) {
                    val stillActive = stateMutex.withLock {
                        pushToTalkGeneration == generation && pushToTalkSessionId != null
                    }
                    if (!stillActive) break
                    val read = record.read(frameBuffer, 0, frameBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read < 0) {
                        throw IllegalStateException("audio capture read failed code=$read")
                    }
                    if (read == 0) {
                        continue
                    }
                    val payload = if (read == frameBuffer.size) {
                        frameBuffer.copyOf()
                    } else {
                        frameBuffer.copyOf(read)
                    }
                    if (!webSocket.send(payload.toByteString())) {
                        throw IllegalStateException("push-to-talk socket send failed")
                    }
                }
            } catch (error: Exception) {
                logger.w("Client push-to-talk capture failed: ${error.message}")
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                stateMutex.withLock {
                    if (pushToTalkGeneration == generation) {
                        pushToTalkCaptureJob = null
                        pushToTalkSocket = null
                    }
                }
                runCatching { webSocket.close(1000, "ptt_capture_stopped") }
            }
        }
        return true
    }

    private fun stopLiveLocked(
        closeSocket: Boolean,
        clearDesiredState: Boolean,
        advanceGeneration: Boolean
    ) {
        if (advanceGeneration) {
            liveGeneration += 1L
        }
        livePlaybackQueue?.close()
        livePlaybackQueue = null
        livePlaybackJob?.cancel()
        livePlaybackJob = null
        if (closeSocket) {
            runCatching { liveSocket?.close(1000, "live_stopped") }
        }
        liveSocket = null
        if (clearDesiredState) {
            desiredLiveTarget = null
            liveSessionId = null
            liveReconnectAttempts = 0
            liveReconnectJob?.cancel()
            liveReconnectJob = null
        }
    }

    private fun stopPushToTalkLocked(
        closeSocket: Boolean,
        clearDesiredState: Boolean,
        advanceGeneration: Boolean
    ) {
        if (advanceGeneration) {
            pushToTalkGeneration += 1L
        }
        pushToTalkCaptureJob?.cancel()
        pushToTalkCaptureJob = null
        if (closeSocket) {
            runCatching { pushToTalkSocket?.close(1000, "ptt_stopped") }
        }
        pushToTalkSocket = null
        if (clearDesiredState) {
            desiredPushToTalkTarget = null
            pushToTalkSessionId = null
            pushToTalkReconnectAttempts = 0
            pushToTalkReconnectJob?.cancel()
            pushToTalkReconnectJob = null
        }
    }

    private fun buildSocketRequest(target: ClientTarget, role: String, sessionId: String): Request {
        val query = buildList {
            add("role=" + URLEncoder.encode(role, StandardCharsets.UTF_8.name()))
            add("streamId=" + URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name()))
            target.token?.takeIf { it.isNotBlank() }?.let {
                add("token=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
            target.deviceId?.takeIf { it.isNotBlank() }?.let {
                add("deviceId=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
        }.joinToString("&")
        val url = "ws://${target.host}:${target.port}/ws/audio?$query"
        return Request.Builder().url(url).build()
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
        }.getOrNull()
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
        }.getOrNull()
    }

    private fun writeFully(track: AudioTrack, frame: ByteArray): Boolean {
        var offset = 0
        while (offset < frame.size) {
            val written = track.write(frame, offset, frame.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) return false
            offset += written
        }
        return true
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        return when {
            attempt <= 1 -> 750L
            attempt == 2 -> 1_500L
            attempt == 3 -> 3_000L
            else -> 5_000L
        }
    }

    private fun newAudioSessionId(role: String): String {
        return "$role-${System.nanoTime().toString(16)}"
    }

    private companion object {
        const val SOCKET_OPEN_TIMEOUT_MS = 4_000L
        const val LIVE_PLAYBACK_QUEUE_CAPACITY = 48
        const val AUDIO_SOCKET_PING_INTERVAL_MS = 20_000L
    }
}
