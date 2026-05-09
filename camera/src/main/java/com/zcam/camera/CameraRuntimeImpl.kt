package com.zcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Environment
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraControl
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.StreamConfig
import com.zcam.core.domain.recording.RecordingEvent
import com.zcam.core.domain.recording.RecordingEventStore
import com.zcam.core.logging.ZCamLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class CameraRuntimeImpl @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dispatchers: DispatcherProvider,
    private val recordingEventStore: RecordingEventStore,
    private val logger: ZCamLogger
) : CameraRuntime, MjpegFrameSource, FramePipelineStatusSource, VideoRecordingPipeline {

    private val latest = AtomicReference(PLACEHOLDER_JPEG)
    private val running = AtomicBoolean(false)
    private val bindMutex = Mutex()
    private val lifecycleOwner = RuntimeLifecycleOwner()
    private val cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zcam-camera-analyzer").apply { isDaemon = true }
    }
    private val eventScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val motionEventDetector = MotionEventDetector()

    private val targetWidth = AtomicInteger(DEFAULT_WIDTH)
    private val targetHeight = AtomicInteger(DEFAULT_HEIGHT)
    private val targetFps = AtomicInteger(DEFAULT_FPS)
    private val producedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val lastFrameEpochMs = AtomicLong(0L)
    private val analysisEnabled = AtomicBoolean(true)
    private val torchEnabled = AtomicBoolean(false)
    private val nightModeEnabled = AtomicBoolean(false)
    private val lowLightBoostSupported = AtomicBoolean(false)
    private val lastControlError = AtomicReference<String?>(null)

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile
    private var previewUseCase: Preview? = null
    @Volatile
    private var analysisUseCase: ImageAnalysis? = null
    @Volatile
    private var videoCaptureUseCase: VideoCapture<Recorder>? = null
    @Volatile
    private var boundCamera: androidx.camera.core.Camera? = null

    private val recordingMutex = Mutex()
    @Volatile
    private var activeRecording: Recording? = null
    @Volatile
    private var activeRecordingFile: File? = null
    @Volatile
    private var activeRecordingFinalize: CompletableDeferred<VideoSegmentResult>? = null

    override suspend fun start(config: StreamConfig) {
        bindMutex.withLock {
            if (running.get()) return

            val normalizedFps = config.fps.coerceAtLeast(1)
            targetWidth.set(config.resolution.width)
            targetHeight.set(config.resolution.height)
            targetFps.set(normalizedFps)
            producedFrames.set(0L)
            droppedFrames.set(0L)
            lastFrameEpochMs.set(0L)
            analysisEnabled.set(true)
            torchEnabled.set(false)
            nightModeEnabled.set(false)
            lowLightBoostSupported.set(false)
            lastControlError.set(null)

            val provider = awaitCameraProvider()
            val encoder = Nv21JpegEncoder()
            val minFrameIntervalMs = (1000L / normalizedFps).coerceAtLeast(1L)

            runOnMainExecutor {
                lifecycleOwner.moveToStarted()
                provider.unbindAll()

                val preview = Preview.Builder()
                    .setTargetResolution(Size(config.resolution.width, config.resolution.height))
                    .build()
                    .also { useCase ->
                        useCase.setSurfaceProvider { request -> request.willNotProvideSurface() }
                    }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(config.resolution.width, config.resolution.height))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)
                    .build()
                val recorder = Recorder.Builder()
                    .setExecutor(cameraExecutor)
                    .setQualitySelector(
                        QualitySelector.fromOrderedList(
                            listOf(Quality.HD, Quality.FHD, Quality.SD, Quality.LOWEST)
                        )
                    )
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)

                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image, encoder, minFrameIntervalMs)
                }

                val camera = runCatching {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                        videoCapture
                    )
                }.recoverCatching {
                    // Some devices cannot handle Preview + Analysis + Video together.
                    analysis.clearAnalyzer()
                    provider.unbindAll()
                    analysisEnabled.set(false)
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        videoCapture
                    )
                }.getOrThrow()

                previewUseCase = preview
                analysisUseCase = analysis
                videoCaptureUseCase = videoCapture
                boundCamera = camera
                lowLightBoostSupported.set(
                    runCatching { camera.cameraInfo.isLowLightBoostSupported }.getOrDefault(false)
                )
            }

            cameraProvider = provider
            running.set(true)
            logger.i("Camera runtime started (${config.resolution.width}x${config.resolution.height}@$normalizedFps)")
        }
    }

    override suspend fun stop() {
        bindMutex.withLock {
            if (!running.get()) return

            logger.i("Camera runtime stop requested")
            val pendingFile = activeRecordingFile
            val finalize = recordingMutex.withLock {
                activeRecording?.stop()
                activeRecording = null
                activeRecordingFile = null
                val pendingFinalize = activeRecordingFinalize
                activeRecordingFinalize = null
                pendingFinalize
            }
            if (finalize != null && !finalize.isCompleted) {
                finalize.complete(buildTimeoutResult(message = "camera runtime stopped", file = pendingFile))
            }
            runOnMainExecutor {
                analysisUseCase?.clearAnalyzer()
                cameraProvider?.unbindAll()
                lifecycleOwner.moveToCreated()
                previewUseCase = null
                analysisUseCase = null
                videoCaptureUseCase = null
                boundCamera = null
                cameraProvider = null
            }
            analysisEnabled.set(false)
            torchEnabled.set(false)
            nightModeEnabled.set(false)
            lowLightBoostSupported.set(false)
            running.set(false)
        }
    }

    override suspend fun isHealthy(): Boolean {
        if (!running.get()) return false
        if (!analysisEnabled.get()) return true
        val lastFrameAt = lastFrameEpochMs.get()
        if (lastFrameAt == 0L) return false
        return (System.currentTimeMillis() - lastFrameAt) <= HEALTHY_FRAME_AGE_MS
    }

    override fun controlsSnapshot(): CameraControlsSnapshot {
        return CameraControlsSnapshot(
            running = running.get(),
            torchEnabled = torchEnabled.get(),
            nightModeEnabled = nightModeEnabled.get(),
            lowLightBoostSupported = lowLightBoostSupported.get(),
            lastError = lastControlError.get()
        )
    }

    override suspend fun setTorch(enabled: Boolean): CameraControlCommandResult = withContext(dispatchers.io) {
        val camera = boundCamera
        if (!running.get() || camera == null) {
            return@withContext failureResult(
                code = CameraControlErrorCode.ENGINE_NOT_READY,
                message = "camera runtime is not active"
            )
        }
        if (enabled && nightModeEnabled.get()) {
            return@withContext failureResult(
                code = CameraControlErrorCode.CONFLICT,
                message = "night mode is enabled; disable night mode before torch"
            )
        }

        runCatching {
            awaitFuture(camera.cameraControl.enableTorch(enabled))
            torchEnabled.set(enabled)
            lastControlError.set(null)
            CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = if (enabled) "torch enabled" else "torch disabled"
            )
        }.getOrElse { error ->
            mapCameraControlFailure(error, fallbackCode = CameraControlErrorCode.INTERNAL_ERROR)
        }
    }

    override suspend fun setNightMode(enabled: Boolean): CameraControlCommandResult = withContext(dispatchers.io) {
        val camera = boundCamera
        if (!running.get() || camera == null) {
            return@withContext failureResult(
                code = CameraControlErrorCode.ENGINE_NOT_READY,
                message = "camera runtime is not active"
            )
        }
        if (enabled && !lowLightBoostSupported.get()) {
            return@withContext failureResult(
                code = CameraControlErrorCode.UNSUPPORTED,
                message = "night mode is not supported by this camera"
            )
        }
        if (enabled && targetFps.get() > 30) {
            return@withContext failureResult(
                code = CameraControlErrorCode.CONFLICT,
                message = "night mode requires stream FPS <= 30"
            )
        }
        if (enabled && torchEnabled.get()) {
            return@withContext failureResult(
                code = CameraControlErrorCode.CONFLICT,
                message = "torch is enabled; disable torch before night mode"
            )
        }

        runCatching {
            awaitFuture(camera.cameraControl.enableLowLightBoostAsync(enabled))
            nightModeEnabled.set(enabled)
            lastControlError.set(null)
            CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = if (enabled) "night mode enabled" else "night mode disabled"
            )
        }.getOrElse { error ->
            val fallbackCode = if (enabled) CameraControlErrorCode.CONFLICT else CameraControlErrorCode.INTERNAL_ERROR
            mapCameraControlFailure(error, fallbackCode = fallbackCode)
        }
    }

    override fun latestFrame(): ByteArray = latest.get()

    override fun snapshot(): FramePipelineStatus = FramePipelineStatus(
        running = running.get(),
        targetWidth = targetWidth.get(),
        targetHeight = targetHeight.get(),
        targetFps = targetFps.get(),
        producedFrames = producedFrames.get(),
        droppedFrames = droppedFrames.get(),
        lastFrameEpochMs = lastFrameEpochMs.get()
    )

    override suspend fun startVideoSegment(outputFile: File) {
        recordingMutex.withLock {
            check(running.get()) { "Camera runtime is not active." }
            check(activeRecording == null) { "Video segment recording already active." }

            val videoCapture = videoCaptureUseCase ?: error("VideoCapture is not bound.")
            outputFile.parentFile?.mkdirs()

            val finalizeDeferred = CompletableDeferred<VideoSegmentResult>()
            activeRecordingFinalize = finalizeDeferred
            activeRecordingFile = outputFile

            val recording = videoCapture.output
                .prepareRecording(
                    appContext,
                    FileOutputOptions.Builder(outputFile).build()
                )
                .start(mainExecutor) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        val result = VideoSegmentResult(
                            file = outputFile,
                            success = event.error == VideoRecordEvent.Finalize.ERROR_NONE,
                            finalizedAtEpochMs = System.currentTimeMillis(),
                            bytesWritten = outputFile.length(),
                            errorCode = if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) null else event.error,
                            errorMessage = event.cause?.message
                        )
                        if (!finalizeDeferred.isCompleted) {
                            finalizeDeferred.complete(result)
                        }
                        activeRecording = null
                        activeRecordingFile = null
                    }
                }

            activeRecording = recording
        }
    }

    override suspend fun stopVideoSegment(timeoutMs: Long): VideoSegmentResult {
        val finalize = recordingMutex.withLock {
            val pendingFinalize = activeRecordingFinalize ?: error("No active video segment recording.")
            val recording = activeRecording
            recording?.stop()
            pendingFinalize
        }

        val result = withTimeoutOrNull(timeoutMs) {
            finalize.await()
        } ?: buildTimeoutResult()

        recordingMutex.withLock {
            activeRecording = null
            activeRecordingFile = null
            activeRecordingFinalize = null
        }
        return result
    }

    override suspend fun abortVideoSegment() {
        var pendingFile: File? = null
        val finalize = recordingMutex.withLock {
            val recording = activeRecording
            val pendingFinalize = activeRecordingFinalize
            pendingFile = activeRecordingFile
            activeRecording = null
            activeRecordingFile = null
            activeRecordingFinalize = null
            recording?.stop()
            pendingFinalize
        }
        if (finalize != null && !finalize.isCompleted) {
            finalize.complete(buildTimeoutResult(message = "segment aborted", file = pendingFile))
        }
    }

    override suspend fun isVideoSegmentActive(): Boolean {
        return recordingMutex.withLock { activeRecording != null }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider = withContext(dispatchers.io) {
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { provider -> continuation.resume(provider) }
                        .onFailure { error -> continuation.resumeWithException(error) }
                },
                mainExecutor
            )
        }
    }

    private fun buildTimeoutResult(
        message: String = "segment finalize timeout",
        file: File? = activeRecordingFile
    ): VideoSegmentResult {
        val safeFile = file ?: File(recordingDirFallback(), "unknown_segment.mp4")
        return VideoSegmentResult(
            file = safeFile,
            success = false,
            finalizedAtEpochMs = System.currentTimeMillis(),
            bytesWritten = safeFile.length(),
            errorCode = null,
            errorMessage = message
        )
    }

    private fun recordingDirFallback(): File {
        val externalBase = appContext.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return if (externalBase != null) {
            File(externalBase, "ZCam/recordings")
        } else {
            File(appContext.filesDir, "zcam_recordings")
        }
    }

    private fun failureResult(
        code: CameraControlErrorCode,
        message: String
    ): CameraControlCommandResult.Failure {
        lastControlError.set(message)
        return CameraControlCommandResult.Failure(
            code = code,
            message = message,
            snapshot = controlsSnapshot()
        )
    }

    private fun mapCameraControlFailure(
        error: Throwable,
        fallbackCode: CameraControlErrorCode
    ): CameraControlCommandResult.Failure {
        logger.w("Camera control failed: ${error.message}")
        val code = when (error) {
            is CameraControl.OperationCanceledException -> CameraControlErrorCode.ENGINE_NOT_READY
            is IllegalStateException -> CameraControlErrorCode.CONFLICT
            else -> fallbackCode
        }
        return failureResult(
            code = code,
            message = error.message ?: "camera control failure"
        )
    }

    private suspend fun <T> awaitFuture(future: ListenableFuture<T>): T {
        return suspendCancellableCoroutine { continuation ->
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { value -> continuation.resume(value) }
                        .onFailure { error -> continuation.resumeWithException(error) }
                },
                mainExecutor
            )
            continuation.invokeOnCancellation {
                future.cancel(true)
            }
        }
    }

    private suspend fun <T> runOnMainExecutor(block: () -> T): T {
        return suspendCancellableCoroutine { continuation ->
            mainExecutor.execute {
                runCatching(block)
                    .onSuccess { value -> continuation.resume(value) }
                    .onFailure { error -> continuation.resumeWithException(error) }
            }
        }
    }

    private fun analyzeFrame(
        image: ImageProxy,
        encoder: Nv21JpegEncoder,
        minFrameIntervalMs: Long
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - encoder.lastProcessedAtMs < minFrameIntervalMs) {
            droppedFrames.incrementAndGet()
            image.close()
            return
        }
        encoder.lastProcessedAtMs = now

        try {
            val event = motionEventDetector.analyze(
                image = image,
                eventEpochMs = System.currentTimeMillis()
            )
            val jpeg = encoder.encode(image)
            latest.set(jpeg)
            producedFrames.incrementAndGet()
            lastFrameEpochMs.set(System.currentTimeMillis())
            if (event != null) {
                eventScope.launch {
                    recordingEventStore.append(event)
                }
            }
        } catch (error: Throwable) {
            droppedFrames.incrementAndGet()
            logger.e(error, "Frame pipeline encode failed")
        } finally {
            image.close()
        }
    }

    private val mainExecutor: Executor by lazy {
        ContextCompat.getMainExecutor(appContext)
    }

    private class RuntimeLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.CREATED
        }

        override fun getLifecycle(): Lifecycle = registry

        fun moveToStarted() {
            registry.currentState = Lifecycle.State.STARTED
        }

        fun moveToCreated() {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    private class Nv21JpegEncoder {
        private var nv21Buffer = ByteArray(0)
        private var rowScratch = ByteArray(0)
        private val output = ByteArrayOutputStream(512 * 1024)

        var lastProcessedAtMs: Long = 0L

        fun encode(image: ImageProxy): ByteArray {
            val width = image.width
            val height = image.height
            val requiredSize = (width * height * 3) / 2
            if (nv21Buffer.size != requiredSize) {
                nv21Buffer = ByteArray(requiredSize)
            }

            copyYPlane(image, nv21Buffer, width, height)
            copyVuPlane(image, nv21Buffer, width, height)

            output.reset()
            val yuvImage = YuvImage(nv21Buffer, ImageFormat.NV21, width, height, null)
            yuvImage.compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, output)
            return output.toByteArray()
        }

        private fun copyYPlane(image: ImageProxy, destination: ByteArray, width: Int, height: Int) {
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer.duplicate()
            copyPlane(
                buffer = yBuffer,
                rowStride = yPlane.rowStride,
                pixelStride = yPlane.pixelStride,
                rowLength = width,
                rowCount = height,
                destination = destination,
                destinationOffset = 0
            )
        }

        private fun copyVuPlane(image: ImageProxy, destination: ByteArray, width: Int, height: Int) {
            val chromaHeight = height / 2
            val destinationOffset = width * height
            val vPlane = image.planes[2]
            val vBuffer = vPlane.buffer.duplicate()

            // OUTPUT_IMAGE_FORMAT_NV21 exposes VU interleaved bytes in plane[2].
            copyPlane(
                buffer = vBuffer,
                rowStride = vPlane.rowStride,
                pixelStride = 1,
                rowLength = width,
                rowCount = chromaHeight,
                destination = destination,
                destinationOffset = destinationOffset
            )
        }

        private fun copyPlane(
            buffer: ByteBuffer,
            rowStride: Int,
            pixelStride: Int,
            rowLength: Int,
            rowCount: Int,
            destination: ByteArray,
            destinationOffset: Int
        ) {
            if (rowScratch.size < rowStride) {
                rowScratch = ByteArray(rowStride)
            }

            var outOffset = destinationOffset
            for (row in 0 until rowCount) {
                val bytesToRead = minOf(rowStride, buffer.remaining())
                if (bytesToRead <= 0) break
                buffer.get(rowScratch, 0, bytesToRead)
                if (pixelStride == 1) {
                    val count = minOf(rowLength, bytesToRead)
                    System.arraycopy(rowScratch, 0, destination, outOffset, count)
                    outOffset += count
                } else {
                    for (col in 0 until rowLength) {
                        val index = col * pixelStride
                        if (index >= bytesToRead) break
                        destination[outOffset++] = rowScratch[index]
                    }
                }
            }
        }
    }

    private class MotionEventDetector {
        private var previousSamples = IntArray(0)
        private var warmupFrames = 0
        private var consecutiveHits = 0
        private var lastEventEpochMs = 0L

        fun analyze(
            image: ImageProxy,
            eventEpochMs: Long
        ): RecordingEvent? {
            val samples = sampleLuma(image)
            if (samples.isEmpty()) return null
            if (previousSamples.isEmpty()) {
                previousSamples = samples
                warmupFrames = 1
                return null
            }

            if (warmupFrames < WARMUP_FRAME_COUNT) {
                previousSamples = samples
                warmupFrames += 1
                return null
            }

            val comparisons = minOf(previousSamples.size, samples.size)
            if (comparisons == 0) {
                previousSamples = samples
                return null
            }

            var changedSamples = 0
            var totalDelta = 0
            var peakDelta = 0
            for (index in 0 until comparisons) {
                val delta = kotlin.math.abs(samples[index] - previousSamples[index])
                totalDelta += delta
                if (delta >= SAMPLE_DELTA_THRESHOLD) {
                    changedSamples += 1
                }
                if (delta > peakDelta) {
                    peakDelta = delta
                }
            }
            previousSamples = samples

            val averageDelta = totalDelta / comparisons.toFloat()
            val changedRatio = changedSamples / comparisons.toFloat()
            val hit = averageDelta >= AVERAGE_DELTA_THRESHOLD &&
                peakDelta >= PEAK_DELTA_THRESHOLD &&
                changedRatio >= CHANGED_SAMPLE_RATIO_THRESHOLD

            consecutiveHits = if (hit) {
                (consecutiveHits + 1).coerceAtMost(CONSECUTIVE_HITS_REQUIRED)
            } else {
                0
            }

            if (consecutiveHits < CONSECUTIVE_HITS_REQUIRED) {
                return null
            }
            if (eventEpochMs - lastEventEpochMs < EVENT_COOLDOWN_MS) {
                return null
            }

            consecutiveHits = 0
            lastEventEpochMs = eventEpochMs
            return RecordingEvent(
                epochMs = eventEpochMs,
                confidencePercent = (averageDelta * 2.5f).toInt().coerceIn(1, 100),
                source = "motion"
            )
        }

        private fun sampleLuma(image: ImageProxy): IntArray {
            val plane = image.planes.firstOrNull() ?: return IntArray(0)
            val buffer = plane.buffer.duplicate()
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride.coerceAtLeast(1)
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0 || !buffer.hasRemaining()) return IntArray(0)

            val xStep = (width / SAMPLE_COLUMNS).coerceAtLeast(MIN_SAMPLE_STEP)
            val yStep = (height / SAMPLE_ROWS).coerceAtLeast(MIN_SAMPLE_STEP)
            val samples = ArrayList<Int>(SAMPLE_COLUMNS * SAMPLE_ROWS)
            var y = 0
            while (y < height) {
                val rowBase = y * rowStride
                var x = 0
                while (x < width) {
                    val index = rowBase + (x * pixelStride)
                    if (index < buffer.limit()) {
                        samples += (buffer.get(index).toInt() and 0xFF)
                    }
                    x += xStep
                }
                y += yStep
            }
            return samples.toIntArray()
        }

        private companion object {
            const val SAMPLE_COLUMNS = 14
            const val SAMPLE_ROWS = 10
            const val MIN_SAMPLE_STEP = 8
            const val WARMUP_FRAME_COUNT = 8
            const val CONSECUTIVE_HITS_REQUIRED = 4
            const val SAMPLE_DELTA_THRESHOLD = 28
            const val AVERAGE_DELTA_THRESHOLD = 18f
            const val PEAK_DELTA_THRESHOLD = 54
            const val CHANGED_SAMPLE_RATIO_THRESHOLD = 0.35f
            const val EVENT_COOLDOWN_MS = 15_000L
        }
    }

    private companion object {
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
        private const val DEFAULT_FPS = 15
        private const val JPEG_QUALITY = 80
        private const val HEALTHY_FRAME_AGE_MS = 5_000L

        // 1x1 JPEG as a fail-safe frame for snapshot/stream before first camera frame arrives.
        val PLACEHOLDER_JPEG: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxAQEBIQEA8PEA8PDw8PDw8PDw8PDw8PFREWFhURFRUYHSggGBolGxUVITEhJSkrLi4uFx8zODMsNygtLisBCgoKDg0OGhAQGi0mHyYtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAAEAAQMBEQACEQEDEQH/xAAXAAEAAwAAAAAAAAAAAAAAAAAAAQID/8QAFhEBAQEAAAAAAAAAAAAAAAAAAAER/9oADAMBAAIQAxAAAAG0A//EABkQAQADAQEAAAAAAAAAAAAAAAIAAREhMf/aAAgBAQABBQJQ0ZQ4x4f/xAAVEQEBAAAAAAAAAAAAAAAAAAAAEf/aAAgBAwEBPwFH/8QAFhEBAQEAAAAAAAAAAAAAAAAAABEh/9oACAECAQE/AYf/xAAbEAACAgMBAAAAAAAAAAAAAAABEQAhMUFhcf/aAAgBAQAGPwKQ4Yq0cYv/xAAZEAEAAgMAAAAAAAAAAAAAAAABABEhMUH/2gAIAQEAAT8h0qVI0rWg/wD/2Q=="
        )
    }
}
