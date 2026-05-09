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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OkHttpLocalAudioTransport @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : LocalAudioTransport {

    private val client = OkHttpClient()
    private val stateMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val transportConfig = AudioTransportConfig()

    private var liveSocket: WebSocket? = null
    private var livePlaybackJob: Job? = null
    private var livePlaybackQueue: Channel<ByteArray>? = null
    private var liveGeneration: Long = 0L

    private var pushToTalkSocket: WebSocket? = null
    private var pushToTalkCaptureJob: Job? = null
    private var pushToTalkGeneration: Long = 0L

    override suspend fun startLiveListen(target: ClientTarget): LocalAudioTransportResult =
        withContext(dispatchers.io) {
            stopLiveListen()

            val ready = CompletableDeferred<LocalAudioTransportResult>()
            val generation = stateMutex.withLock {
                liveGeneration += 1L
                liveGeneration
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runtimeScope.launch {
                        val started = stateMutex.withLock {
                            if (liveGeneration != generation) {
                                false
                            } else {
                                startLivePlaybackLocked(generation, webSocket)
                            }
                        }
                        val result = if (started) {
                            LocalAudioTransportResult.Success
                        } else {
                            runCatching { webSocket.close(1011, "live_playback_unavailable") }
                            LocalAudioTransportResult.Failure("live_playback_unavailable")
                        }
                        if (!ready.isCompleted) {
                            ready.complete(result)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    runtimeScope.launch {
                        val queue = stateMutex.withLock {
                            if (liveGeneration == generation) livePlaybackQueue else null
                        } ?: return@launch
                        queue.trySend(bytes.toByteArray())
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    runtimeScope.launch {
                        stateMutex.withLock {
                            if (liveGeneration == generation) {
                                stopLiveLocked(closeSocket = false)
                            }
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
                        }
                        stateMutex.withLock {
                            if (liveGeneration == generation) {
                                stopLiveLocked(closeSocket = false)
                            }
                        }
                    }
                }
            }

            client.newWebSocket(buildSocketRequest(target, "live"), listener)
            withTimeoutOrNull(SOCKET_OPEN_TIMEOUT_MS) {
                ready.await()
            } ?: LocalAudioTransportResult.Failure("live_socket_timeout")
        }

    override suspend fun stopLiveListen() = withContext(dispatchers.io) {
        stateMutex.withLock {
            stopLiveLocked(closeSocket = true)
        }
    }

    override suspend fun startPushToTalk(target: ClientTarget): LocalAudioTransportResult =
        withContext(dispatchers.io) {
            stopPushToTalk()

            val ready = CompletableDeferred<LocalAudioTransportResult>()
            val generation = stateMutex.withLock {
                pushToTalkGeneration += 1L
                pushToTalkGeneration
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    runtimeScope.launch {
                        val started = stateMutex.withLock {
                            if (pushToTalkGeneration != generation) {
                                false
                            } else {
                                startPushToTalkCaptureLocked(generation, webSocket)
                            }
                        }
                        val result = if (started) {
                            LocalAudioTransportResult.Success
                        } else {
                            runCatching { webSocket.close(1011, "ptt_capture_unavailable") }
                            LocalAudioTransportResult.Failure("ptt_capture_unavailable")
                        }
                        if (!ready.isCompleted) {
                            ready.complete(result)
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    runtimeScope.launch {
                        stateMutex.withLock {
                            if (pushToTalkGeneration == generation) {
                                stopPushToTalkLocked(closeSocket = false)
                            }
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
                        }
                        stateMutex.withLock {
                            if (pushToTalkGeneration == generation) {
                                stopPushToTalkLocked(closeSocket = false)
                            }
                        }
                    }
                }
            }

            client.newWebSocket(buildSocketRequest(target, "ptt"), listener)
            withTimeoutOrNull(SOCKET_OPEN_TIMEOUT_MS) {
                ready.await()
            } ?: LocalAudioTransportResult.Failure("ptt_socket_timeout")
        }

    override suspend fun stopPushToTalk() = withContext(dispatchers.io) {
        stateMutex.withLock {
            stopPushToTalkLocked(closeSocket = true)
        }
    }

    override suspend fun stopAll() = withContext(dispatchers.io) {
        stateMutex.withLock {
            stopLiveLocked(closeSocket = true)
            stopPushToTalkLocked(closeSocket = true)
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
                    val stillActive = stateMutex.withLock { liveGeneration == generation }
                    if (!stillActive) break
                    writeFully(track, frame)
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
                    val stillActive = stateMutex.withLock { pushToTalkGeneration == generation }
                    if (!stillActive) break
                    val read = record.read(frameBuffer, 0, frameBuffer.size, AudioRecord.READ_BLOCKING)
                    if (read <= 0) continue
                    val payload = if (read == frameBuffer.size) {
                        frameBuffer.copyOf()
                    } else {
                        frameBuffer.copyOf(read)
                    }
                    if (!webSocket.send(payload.toByteString())) {
                        break
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

    private fun stopLiveLocked(closeSocket: Boolean) {
        liveGeneration += 1L
        livePlaybackQueue?.close()
        livePlaybackQueue = null
        livePlaybackJob?.cancel()
        livePlaybackJob = null
        if (closeSocket) {
            runCatching { liveSocket?.close(1000, "live_stopped") }
        }
        liveSocket = null
    }

    private fun stopPushToTalkLocked(closeSocket: Boolean) {
        pushToTalkGeneration += 1L
        pushToTalkCaptureJob?.cancel()
        pushToTalkCaptureJob = null
        if (closeSocket) {
            runCatching { pushToTalkSocket?.close(1000, "ptt_stopped") }
        }
        pushToTalkSocket = null
    }

    private fun buildSocketRequest(target: ClientTarget, role: String): Request {
        val query = buildList {
            add("role=" + URLEncoder.encode(role, StandardCharsets.UTF_8.name()))
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

    private fun writeFully(track: AudioTrack, frame: ByteArray) {
        var offset = 0
        while (offset < frame.size) {
            val written = track.write(frame, offset, frame.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            offset += written
        }
    }

    private companion object {
        const val SOCKET_OPEN_TIMEOUT_MS = 4_000L
        const val LIVE_PLAYBACK_QUEUE_CAPACITY = 48
    }
}
