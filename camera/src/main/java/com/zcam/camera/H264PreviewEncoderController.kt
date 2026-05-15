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
    private val actualWidth = AtomicInteger(0)
    private val actualHeight = AtomicInteger(0)
    private val disabledReason = AtomicReference<String?>(null)

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible

    @Volatile
    private var previewConfig: PreviewStreamConfig = PreviewStreamConfig()

    @Volatile
    private var streamConfig: H264PreviewStreamConfig? = null

    @Volatile
    private var selectedEncoderInfo: MediaCodecInfo? = null

    @Volatile
    private var lastEncoderStartAttemptAtMs: Long = 0L

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
        selectedEncoderInfo = encoderInfo
        streamConfig = null
        disabledReason.set(null)
        lastError.set(null)
        estimatedBitrateKbps.set(0)
        sentFps.set(0)
        emittedFrames.set(0L)
        droppedFrames.set(0L)
        actualWidth.set(0)
        actualHeight.set(0)
        windowStartedAtMs = 0L
        windowBytes = 0L
        windowFrames = 0
        lastEncoderStartAttemptAtMs = 0L
        running.set(true)
        executor.execute(::pumpLoop)
        return true
    }

    fun stop() {
        running.set(false)
        pendingFrame.set(null)
        subscribers.clear()
        streamConfig = null
        selectedEncoderInfo = null
        disabledReason.set(null)
        actualWidth.set(0)
        actualHeight.set(0)
        lastEncoderStartAttemptAtMs = 0L
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
        if (!acceptsFrames()) return
        val copy = nv21.copyOf()
        if (pendingFrame.getAndSet(PreviewFrame(copy, width, height, presentationTimeUs)) != null) {
            droppedFrames.incrementAndGet()
        }
    }

    fun acceptsFrames(): Boolean {
        return running.get() && disabledReason.get() == null
    }

    fun registerSubscriber(
        subscriberId: String,
        onConfig: (H264PreviewStreamConfig) -> Unit,
        onAccessUnit: (H264PreviewAccessUnit) -> Unit
    ): Boolean {
        if (previewConfig.transport != PreviewTransport.H264 || !acceptsFrames()) {
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
            actualWidth = actualWidth.get(),
            actualHeight = actualHeight.get(),
            targetFps = previewConfig.fps,
            targetBitrateKbps = previewConfig.bitrateKbps,
            estimatedBitrateKbps = estimatedBitrateKbps.get(),
            sentFps = sentFps.get(),
            subscriberCount = subscribers.size,
            encoderRunning = running.get() && encoder != null,
            mjpegFallbackAvailable = true,
            droppedFrames = droppedFrames.get(),
            lastError = disabledReason.get() ?: lastError.get()
        )
    }

    fun requestSyncFrame() {
        if (!acceptsFrames() || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun pumpLoop() {
        runCatching {
            while (running.get()) {
                val frame = pendingFrame.getAndSet(null)
                if (frame != null) {
                    if (!ensureEncoderForFrame(frame.width, frame.height)) {
                        droppedFrames.incrementAndGet()
                        continue
                    }
                    queueInputFrame(frame)
                    safeDrainEncoder()
                } else {
                    safeDrainEncoder()
                    try {
                        Thread.sleep(IDLE_POLL_MS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
            safeDrainEncoder()
        }.onFailure { error ->
            failEncoder("encoder loop failed: ${error.message ?: "unknown"}", error)
        }
    }

    private fun ensureEncoderForFrame(
        width: Int,
        height: Int
    ): Boolean {
        if (width <= 0 || height <= 0) {
            disableH264("Invalid preview frame size ${width}x${height}; MJPEG fallback active")
            return false
        }
        if (width % 2 != 0 || height % 2 != 0) {
            disableH264("Unsupported odd preview frame size ${width}x${height}; MJPEG fallback active")
            return false
        }
        val currentEncoder = encoder
        if (currentEncoder != null && actualWidth.get() == width && actualHeight.get() == height) {
            return true
        }

        val now = System.currentTimeMillis()
        if (now - lastEncoderStartAttemptAtMs < START_RETRY_BACKOFF_MS) {
            return false
        }
        lastEncoderStartAttemptAtMs = now
        releaseEncoder()

        val encoderInfo = selectedEncoderInfo ?: selectEncoder() ?: run {
            lastError.set("No H.264 encoder available")
            return false
        }
        selectedEncoderInfo = encoderInfo

        return runCatching {
            val capabilities = encoderInfo.getCapabilitiesForType(MIME_TYPE)
            val videoCapabilities = capabilities.videoCapabilities
            if (!videoCapabilities.isSizeSupported(width, height)) {
                disableH264("H.264 encoder ${encoderInfo.name} does not support ${width}x${height}; MJPEG fallback active")
                return false
            }
            val codec = MediaCodec.createByCodecName(encoderInfo.name)
            colorFormat = selectColorFormat(capabilities)
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, previewConfig.bitrateKbps * 1_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, previewConfig.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SEC)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                    setFloat(MediaFormat.KEY_OPERATING_RATE, previewConfig.fps.toFloat())
                }
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            encoder = codec
            streamConfig = null
            actualWidth.set(width)
            actualHeight.set(height)
            lastError.set(null)
            subscribers.values.forEach { subscriber -> subscriber.started = false }
            if (width != previewConfig.resolution.width || height != previewConfig.resolution.height) {
                logger.w(
                    "Preview H.264 encoder using actual frame size ${width}x${height} instead of requested ${previewConfig.resolution.width}x${previewConfig.resolution.height}"
                )
            }
            true
        }.getOrElse { error ->
            actualWidth.set(0)
            actualHeight.set(0)
            failEncoder("encoder start failed for ${width}x${height}: ${error.message ?: "unknown"}", error)
            false
        }
    }

    private fun queueInputFrame(frame: PreviewFrame) {
        val codec = encoder ?: return
        val index = runCatching { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrElse { error ->
            failEncoder("encoder input dequeue failed: ${error.message ?: "unknown"}", error)
            return
        }
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
            droppedFrames.incrementAndGet()
            runCatching { codec.queueInputBuffer(index, 0, 0, frame.presentationTimeUs, 0) }
            failEncoder("encoder input failure: ${error.message ?: "unknown"}", error)
        }
    }

    private fun safeDrainEncoder() {
        runCatching {
            drainEncoder()
        }.onFailure { error ->
            failEncoder("encoder drain failure: ${error.message ?: "unknown"}", error)
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

    private fun failEncoder(
        message: String,
        error: Throwable? = null
    ) {
        disableH264("$message; MJPEG fallback active", error)
    }

    private fun disableH264(
        message: String,
        error: Throwable? = null
    ) {
        running.set(false)
        disabledReason.set(message)
        lastError.set(message)
        if (error != null) {
            logger.w("Preview H.264 $message")
        } else {
            logger.w("Preview H.264 $message")
        }
        releaseEncoder()
        streamConfig = null
        actualWidth.set(0)
        actualHeight.set(0)
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
        const val START_RETRY_BACKOFF_MS = 1_500L
    }
}
