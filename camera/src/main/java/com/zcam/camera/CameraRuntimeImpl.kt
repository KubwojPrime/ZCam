package com.zcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.StreamConfig
import com.zcam.core.logging.ZCamLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
    private val logger: ZCamLogger
) : CameraRuntime, MjpegFrameSource, FramePipelineStatusSource {

    private val latest = AtomicReference(PLACEHOLDER_JPEG)
    private val running = AtomicBoolean(false)
    private val bindMutex = Mutex()
    private val lifecycleOwner = RuntimeLifecycleOwner()
    private val cameraExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zcam-camera-analyzer").apply { isDaemon = true }
    }

    private val targetWidth = AtomicInteger(DEFAULT_WIDTH)
    private val targetHeight = AtomicInteger(DEFAULT_HEIGHT)
    private val targetFps = AtomicInteger(DEFAULT_FPS)
    private val producedFrames = AtomicLong(0L)
    private val droppedFrames = AtomicLong(0L)
    private val lastFrameEpochMs = AtomicLong(0L)

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile
    private var previewUseCase: Preview? = null
    @Volatile
    private var analysisUseCase: ImageAnalysis? = null

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

                analysis.setAnalyzer(cameraExecutor) { image ->
                    analyzeFrame(image, encoder, minFrameIntervalMs)
                }

                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                previewUseCase = preview
                analysisUseCase = analysis
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
            runOnMainExecutor {
                analysisUseCase?.clearAnalyzer()
                cameraProvider?.unbindAll()
                lifecycleOwner.moveToCreated()
                previewUseCase = null
                analysisUseCase = null
                cameraProvider = null
            }
            running.set(false)
        }
    }

    override suspend fun isHealthy(): Boolean {
        if (!running.get()) return false
        val lastFrameAt = lastFrameEpochMs.get()
        if (lastFrameAt == 0L) return false
        return (System.currentTimeMillis() - lastFrameAt) <= HEALTHY_FRAME_AGE_MS
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
            val jpeg = encoder.encode(image)
            latest.set(jpeg)
            producedFrames.incrementAndGet()
            lastFrameEpochMs.set(System.currentTimeMillis())
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
