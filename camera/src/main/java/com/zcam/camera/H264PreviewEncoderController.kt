package com.zcam.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import com.zcam.core.domain.config.PreviewStreamConfig
import com.zcam.core.domain.config.PreviewTransport
import com.zcam.core.logging.ZCamLogger
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class H264PreviewEncoderController(
    private val logger: ZCamLogger
) {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zcam-preview-h264").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<PreviewFrame?>(null)
    private val subscribers = ConcurrentHashMap<String, Subscriber>()
    private val lastError = AtomicReference<String?>(null)
    private val estimatedBitrateKbps = AtomicInteger(0)
    private val sentFps = AtomicInteger(0)
    private val emittedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    @Volatile
    private var previewConfig: PreviewStreamConfig = PreviewStreamConfig()

    @Volatile
    private var streamConfig: H264PreviewStreamConfig? = null

    private var windowStartedAtMs: Long = 0L
    private var windowBytes: Long = 0L
    private var windowFrames: Int = 0

    fun start(config: PreviewStreamConfig): Boolean {
        stop()
        previewConfig = config
        if (config.transport != PreviewTransport.H264) {
            return false
        }

        val encoderInfo = selectEncoder() ?: run {
            lastError.set("No H.264 encoder available")
            return false
        }

        return runCatching {
            val codec = MediaCodec.createByCodecName(encoderInfo.name)
            val capabilities = encoderInfo.getCapabilitiesForType(MIME_TYPE)
            colorFormat = selectColorFormat(capabilities)
            val format = MediaFormat.createVideoFormat(
                MIME_TYPE,
                config.resolution.width,
                config.resolution.height
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SEC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setFloat(MediaFormat.KEY_OPERATING_RATE, config.fps.toFloat())
                }
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            streamConfig = null
            lastError.set(null)
            estimatedBitrateKbps.set(0)
            sentFps.set(0)
            emittedFrames.set(0L)
            droppedFrames.set(0L)
            windowStartedAtMs = 0L
            windowBytes = 0L
            windowFrames = 0
            running.set(true)
            executor.execute(::pumpLoop)
            true
        }.getOrElse { error ->
            lastError.set(error.message ?: "encoder start failed")
            logger.w("Preview H.264 encoder start failed: ${error.message}")
            releaseEncoder()
            false
        }
    }

    fun stop() {
        running.set(false)
        pendingFrame.set(null)
        subscribers.clear()
        streamConfig = null
        releaseEncoder()
        estimatedBitrateKbps.set(0)
        sentFps.set(0)
    }

    fun submitFrame(
        nv21: ByteArray,
        width: Int,
        height: Int,
        presentationTimeUs: Long
    ) {
        if (!running.get()) return
        if (width != previewConfig.resolution.width || height != previewConfig.resolution.height) {
            droppedFrames.incrementAndGet()
            return
        }
        val copy = nv21.copyOf()
        if (pendingFrame.getAndSet(PreviewFrame(copy, width, height, presentationTimeUs)) != null) {
            droppedFrames.incrementAndGet()
        }
    }

    fun registerSubscriber(
        subscriberId: String,
        onConfig: (H264PreviewStreamConfig) -> Unit,
        onAccessUnit: (H264PreviewAccessUnit) -> Unit
    ): Boolean {
        if (previewConfig.transport != PreviewTransport.H264 || !running.get()) {
            return false
        }
        subscribers[subscriberId] = Subscriber(onConfig = onConfig, onAccessUnit = onAccessUnit)
        streamConfig?.let { config ->
            runCatching { onConfig(config) }
        }
        requestSyncFrame()
        return true
    }

    fun unregisterSubscriber(subscriberId: String) {
        subscribers.remove(subscriberId)
    }

    fun diagnostics(): PreviewStreamingDiagnostics {
        return PreviewStreamingDiagnostics(
            transport = previewConfig.transport,
            targetWidth = previewConfig.resolution.width,
            targetHeight = previewConfig.resolution.height,
            targetFps = previewConfig.fps,
            targetBitrateKbps = previewConfig.bitrateKbps,
            estimatedBitrateKbps = estimatedBitrateKbps.get(),
            sentFps = sentFps.get(),
            subscriberCount = subscribers.size,
            encoderRunning = running.get() && encoder != null,
            mjpegFallbackAvailable = true,
            lastError = lastError.get()
        )
    }

    fun requestSyncFrame() {
        if (!running.get() || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun pumpLoop() {
        while (running.get()) {
            val frame = pendingFrame.getAndSet(null)
            if (frame != null) {
                queueInputFrame(frame)
                drainEncoder()
            } else {
                drainEncoder()
                try {
                    Thread.sleep(IDLE_POLL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        drainEncoder()
    }

    private fun queueInputFrame(frame: PreviewFrame) {
        val codec = encoder ?: return
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            droppedFrames.incrementAndGet()
            return
        }

        runCatching {
            val buffer = codec.getInputBuffer(index) ?: return
            buffer.clear()
            writeFrameToInputBuffer(
                inputBuffer = buffer,
                nv21 = frame.nv21,
                width = frame.width,
                height = frame.height,
                colorFormat = colorFormat
            )
            codec.queueInputBuffer(index, 0, frame.nv21.size, frame.presentationTimeUs, 0)
        }.onFailure { error ->
            lastError.set(error.message ?: "encoder input failure")
            droppedFrames.incrementAndGet()
            runCatching { codec.queueInputBuffer(index, 0, 0, frame.presentationTimeUs, 0) }
        }
    }

    private fun drainEncoder() {
        val codec = encoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            when (val index = codec.dequeueOutputBuffer(bufferInfo, OUTPUT_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val nextConfig = buildStreamConfig(codec.outputFormat)
                    if (nextConfig != null) {
                        streamConfig = nextConfig
                        subscribers.values.forEach { subscriber ->
                            subscriber.started = false
                            runCatching { subscriber.onConfig(nextConfig) }
                        }
                    }
                }
                else -> {
                    if (index < 0) return
                    val outputBuffer = codec.getOutputBuffer(index)
                    if (outputBuffer != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val packet = ByteArray(bufferInfo.size)
                        outputBuffer.get(packet)
                        dispatchEncodedFrame(
                            H264PreviewAccessUnit(
                                data = packet,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            )
                        )
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }
    }

    private fun dispatchEncodedFrame(accessUnit: H264PreviewAccessUnit) {
        val config = streamConfig ?: return
        emittedFrames.incrementAndGet()
        updateRollingStats(accessUnit.data.size)
        subscribers.values.forEach { subscriber ->
            if (!subscriber.started) {
                if (!accessUnit.isKeyFrame) return@forEach
                subscriber.started = true
                runCatching { subscriber.onConfig(config) }
            }
            runCatching { subscriber.onAccessUnit(accessUnit) }
        }
    }

    private fun updateRollingStats(packetBytes: Int) {
        val now = System.currentTimeMillis()
        if (windowStartedAtMs <= 0L) {
            windowStartedAtMs = now
        }
        windowBytes += packetBytes.toLong()
        windowFrames += 1

        val elapsedMs = (now - windowStartedAtMs).coerceAtLeast(1L)
        if (elapsedMs >= 1_000L) {
            estimatedBitrateKbps.set(((windowBytes * 8L) / elapsedMs).toInt())
            sentFps.set(((windowFrames * 1_000L) / elapsedMs).toInt())
            windowStartedAtMs = now
            windowBytes = 0L
            windowFrames = 0
        }
    }

    private fun buildStreamConfig(format: MediaFormat): H264PreviewStreamConfig? {
        val csd0 = format.getByteBuffer("csd-0")?.copyRemainingBytes() ?: return null
        val csd1 = format.getByteBuffer("csd-1")?.copyRemainingBytes() ?: return null
        return H264PreviewStreamConfig(
            codecMime = MIME_TYPE,
            width = format.getInteger(MediaFormat.KEY_WIDTH),
            height = format.getInteger(MediaFormat.KEY_HEIGHT),
            fps = previewConfig.fps,
            bitrateKbps = previewConfig.bitrateKbps,
            keyframeIntervalSec = KEYFRAME_INTERVAL_SEC,
            csd0 = csd0,
            csd1 = csd1
        )
    }

    private fun releaseEncoder() {
        val codec = encoder ?: return
        encoder = null
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun selectEncoder(): MediaCodecInfo? {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MIME_TYPE, ignoreCase = true) } }

        if (codecInfos.isEmpty()) return null
        return codecInfos
            .sortedWith(
                compareByDescending<MediaCodecInfo> { info -> isHardwareAccelerated(info) }
                    .thenBy { info -> info.name }
            )
            .firstOrNull()
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            val name = info.name.lowercase()
            !name.contains("google") && !name.contains("sw") && !name.contains("software")
        }
    }

    private fun selectColorFormat(capabilities: MediaCodecInfo.CodecCapabilities): Int {
        val preferredFormats = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        return preferredFormats.firstOrNull { candidate ->
            capabilities.colorFormats.contains(candidate)
        } ?: capabilities.colorFormats.firstOrNull() ?: MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    private fun writeFrameToInputBuffer(
        inputBuffer: ByteBuffer,
        nv21: ByteArray,
        width: Int,
        height: Int,
        colorFormat: Int
    ) {
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> writeNv21AsNv12(
                inputBuffer,
                nv21,
                width,
                height
            )
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> writeNv21AsI420(
                inputBuffer,
                nv21,
                width,
                height
            )
            else -> writeNv21AsI420(inputBuffer, nv21, width, height)
        }
    }

    private fun writeNv21AsNv12(
        buffer: ByteBuffer,
        nv21: ByteArray,
        width: Int,
        height: Int
    ) {
        val frameSize = width * height
        buffer.put(nv21, 0, frameSize)
        var chromaIndex = frameSize
        while (chromaIndex + 1 < nv21.size) {
            val v = nv21[chromaIndex]
            val u = nv21[chromaIndex + 1]
            buffer.put(u)
            buffer.put(v)
            chromaIndex += 2
        }
    }

    private fun writeNv21AsI420(
        buffer: ByteBuffer,
        nv21: ByteArray,
        width: Int,
        height: Int
    ) {
        val frameSize = width * height
        val chromaSize = frameSize / 4
        buffer.put(nv21, 0, frameSize)

        val uPlane = ByteArray(chromaSize)
        val vPlane = ByteArray(chromaSize)
        var inputIndex = frameSize
        var planeIndex = 0
        while (inputIndex + 1 < nv21.size && planeIndex < chromaSize) {
            vPlane[planeIndex] = nv21[inputIndex]
            uPlane[planeIndex] = nv21[inputIndex + 1]
            inputIndex += 2
            planeIndex += 1
        }
        buffer.put(uPlane)
        buffer.put(vPlane)
    }

    private fun ByteBuffer.copyRemainingBytes(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    private data class PreviewFrame(
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        val presentationTimeUs: Long
    )

    private data class Subscriber(
        val onConfig: (H264PreviewStreamConfig) -> Unit,
        val onAccessUnit: (H264PreviewAccessUnit) -> Unit,
        var started: Boolean = false
    )

    private companion object {
        const val MIME_TYPE = "video/avc"
        const val KEYFRAME_INTERVAL_SEC = 1
        const val IDLE_POLL_MS = 4L
        const val INPUT_TIMEOUT_US = 0L
        const val OUTPUT_TIMEOUT_US = 0L
    }
}
