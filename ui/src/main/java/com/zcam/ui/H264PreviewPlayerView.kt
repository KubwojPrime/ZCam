package com.zcam.ui

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.TimeUnit

internal data class H264PreviewRenderState(
    val statusLabel: String,
    val diagnosticsLabel: String,
    val tone: StatusTone
)

internal class H264PreviewPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs), SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var previewSocketUrl: String = ""
    private var webSocket: WebSocket? = null
    private var decoder: MediaCodec? = null
    private var renderSurface: Surface? = null
    private var streamConfig: PreviewSocketConfig? = null
    private var manualSocketClose = false
    private var reconnectScheduled = false
    private var lastRenderedAtEpochMs: Long = 0L
    private var fpsWindowStartedAtMs: Long = 0L
    private var fpsWindowFrames: Int = 0
    private var receivedFps: Int = 0
    private var attached = false
    private var socketOpenedAtEpochMs: Long = 0L

    var statusListener: ((H264PreviewRenderState) -> Unit)? = null

    init {
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        surfaceView.holder.addCallback(this)
    }

    fun bindPreviewSocketUrl(url: String) {
        if (previewSocketUrl == url) return
        previewSocketUrl = url
        reconnectNow("source changed")
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        startWatchdog()
        openSocketIfReady()
    }

    override fun onDetachedFromWindow() {
        attached = false
        stopWatchdog()
        mainHandler.removeCallbacksAndMessages(null)
        closeSocket()
        releaseDecoder()
        super.onDetachedFromWindow()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        renderSurface = holder.surface
        configureDecoderIfReady()
        openSocketIfReady()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        renderSurface = null
        releaseDecoder()
    }

    private fun openSocketIfReady() {
        if (!attached || renderSurface == null || previewSocketUrl.isBlank() || webSocket != null) return

        manualSocketClose = false
        emitState(
            status = "Preview reconnecting",
            diagnostics = "decoder: opening H.264 socket",
            tone = StatusTone.WARNING
        )
        socketOpenedAtEpochMs = System.currentTimeMillis()
        lastRenderedAtEpochMs = 0L
        val request = Request.Builder()
            .url(previewSocketUrl)
            .build()
        webSocket = sharedSocketClient.newWebSocket(request, socketListener)
    }

    private fun closeSocket() {
        manualSocketClose = true
        reconnectScheduled = false
        socketOpenedAtEpochMs = 0L
        webSocket?.cancel()
        webSocket = null
    }

    private fun reconnectNow(reason: String) {
        closeSocket()
        releaseDecoder()
        if (!attached || previewSocketUrl.isBlank()) return
        scheduleReconnect(reason, RECONNECT_BASE_DELAY_MS)
    }

    private fun scheduleReconnect(reason: String, delayMs: Long) {
        if (!attached || previewSocketUrl.isBlank() || reconnectScheduled) return
        reconnectScheduled = true
        emitState(
            status = "Preview reconnecting",
            diagnostics = "decoder: $reason",
            tone = StatusTone.WARNING
        )
        mainHandler.postDelayed(
            {
                reconnectScheduled = false
                if (attached) {
                    openSocketIfReady()
                }
            },
            delayMs
        )
    }

    private fun configureDecoderIfReady() {
        val surface = renderSurface ?: return
        val config = streamConfig ?: return

        releaseDecoder()
        runCatching {
            val codec = MediaCodec.createDecoderByType(config.codecMime)
            val format = MediaFormat.createVideoFormat(config.codecMime, config.width, config.height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(config.csd0))
                setByteBuffer("csd-1", ByteBuffer.wrap(config.csd1))
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            emitState(
                status = "Preview loading",
                diagnostics = "decoder: configured ${config.width}x${config.height} H.264",
                tone = StatusTone.WARNING
            )
        }.onFailure { error ->
            emitState(
                status = "Preview unavailable",
                diagnostics = "decoder setup failed: ${error.message}",
                tone = StatusTone.ERROR
            )
            scheduleReconnect("decoder setup failed", RECONNECT_MAX_DELAY_MS)
        }
    }

    private fun releaseDecoder() {
        val codec = decoder ?: return
        decoder = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun handleConfigMessage(message: String) {
        runCatching {
            val payload = JSONObject(message)
            if (!payload.optString("type").equals("config", ignoreCase = true)) {
                return@runCatching
            }
            streamConfig = PreviewSocketConfig(
                codecMime = payload.optString("codec", "video/avc"),
                width = payload.optInt("width"),
                height = payload.optInt("height"),
                fps = payload.optInt("fps"),
                bitrateKbps = payload.optInt("bitrateKbps"),
                csd0 = Base64.getDecoder().decode(payload.optString("csd0", "")),
                csd1 = Base64.getDecoder().decode(payload.optString("csd1", ""))
            )
            configureDecoderIfReady()
        }.onFailure { error ->
            emitState(
                status = "Preview unavailable",
                diagnostics = "config parse failed: ${error.message}",
                tone = StatusTone.ERROR
            )
            scheduleReconnect("config parse failed", RECONNECT_MAX_DELAY_MS)
        }
    }

    private fun handleVideoFrame(bytes: ByteArray) {
        val codec = decoder ?: return
        val inputIndex = codec.dequeueInputBuffer(0L)
        if (inputIndex < 0) {
            return
        }

        runCatching {
            val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
            inputBuffer.clear()
            inputBuffer.put(bytes)
            codec.queueInputBuffer(
                inputIndex,
                0,
                bytes.size,
                System.nanoTime() / 1_000L,
                0
            )
            drainDecoder(codec)
        }.onFailure { error ->
            emitState(
                status = "Preview reconnecting",
                diagnostics = "decoder input failed: ${error.message}",
                tone = StatusTone.WARNING
            )
            scheduleReconnect("decoder input failed", RECONNECT_MAX_DELAY_MS)
        }
    }

    private fun drainDecoder(codec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (outputIndex < 0) return
                    codec.releaseOutputBuffer(outputIndex, true)
                    updateRenderStats()
                }
            }
        }
    }

    private fun updateRenderStats() {
        val now = System.currentTimeMillis()
        lastRenderedAtEpochMs = now
        if (fpsWindowStartedAtMs <= 0L) {
            fpsWindowStartedAtMs = now
        }
        fpsWindowFrames += 1
        val elapsedMs = (now - fpsWindowStartedAtMs).coerceAtLeast(1L)
        if (elapsedMs >= 1_000L) {
            receivedFps = ((fpsWindowFrames * 1_000L) / elapsedMs).toInt()
            fpsWindowStartedAtMs = now
            fpsWindowFrames = 0
        }
        val config = streamConfig
        emitState(
            status = "Preview live",
            diagnostics = buildString {
                append("decoder: ${receivedFps} FPS")
                if (config != null) {
                    append(" | ${config.width}x${config.height}")
                    append(" | ${config.bitrateKbps} kbps")
                }
            },
            tone = StatusTone.HEALTHY
        )
    }

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun stopWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private fun emitState(
        status: String,
        diagnostics: String,
        tone: StatusTone
    ) {
        mainHandler.post {
            statusListener?.invoke(
                H264PreviewRenderState(
                    statusLabel = status,
                    diagnosticsLabel = diagnostics,
                    tone = tone
                )
            )
        }
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            emitState(
                status = "Preview loading",
                diagnostics = "decoder: socket open, waiting for config",
                tone = StatusTone.WARNING
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleConfigMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleVideoFrame(bytes.toByteArray())
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            this@H264PreviewPlayerView.webSocket = null
            if (manualSocketClose || !attached) return
            emitState(
                status = "Preview reconnecting",
                diagnostics = "decoder socket failed: ${t.message}",
                tone = StatusTone.WARNING
            )
            scheduleReconnect("socket failed", RECONNECT_MAX_DELAY_MS)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            this@H264PreviewPlayerView.webSocket = null
            if (manualSocketClose || !attached) return
            emitState(
                status = "Preview reconnecting",
                diagnostics = "decoder socket closed: $reason",
                tone = StatusTone.WARNING
            )
            scheduleReconnect("socket closed", RECONNECT_BASE_DELAY_MS)
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!attached) return
            val now = System.currentTimeMillis()
            val openedTooLongWithoutFrame =
                webSocket != null &&
                    socketOpenedAtEpochMs > 0L &&
                    lastRenderedAtEpochMs <= 0L &&
                    now - socketOpenedAtEpochMs > STALL_TIMEOUT_MS
            val stalledAfterFrame =
                webSocket != null &&
                    lastRenderedAtEpochMs > 0L &&
                    now - lastRenderedAtEpochMs > STALL_TIMEOUT_MS
            if (openedTooLongWithoutFrame || stalledAfterFrame) {
                reconnectNow("stream stalled")
            }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private data class PreviewSocketConfig(
        val codecMime: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrateKbps: Int,
        val csd0: ByteArray,
        val csd1: ByteArray
    )

    private companion object {
        val sharedSocketClient: OkHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
        const val WATCHDOG_INTERVAL_MS = 1_000L
        const val STALL_TIMEOUT_MS = 3_500L
        const val RECONNECT_BASE_DELAY_MS = 350L
        const val RECONNECT_MAX_DELAY_MS = 1_500L
    }
}
