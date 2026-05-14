package com.zcam.camera

import com.zcam.core.domain.config.PreviewTransport

data class PreviewStreamingDiagnostics(
    val transport: PreviewTransport,
    val targetWidth: Int,
    val targetHeight: Int,
    val actualWidth: Int,
    val actualHeight: Int,
    val targetFps: Int,
    val targetBitrateKbps: Int,
    val estimatedBitrateKbps: Int,
    val sentFps: Int,
    val subscriberCount: Int,
    val encoderRunning: Boolean,
    val mjpegFallbackAvailable: Boolean,
    val droppedFrames: Long = 0L,
    val lastError: String? = null
)

data class H264PreviewStreamConfig(
    val codecMime: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val keyframeIntervalSec: Int,
    val csd0: ByteArray,
    val csd1: ByteArray
)

data class H264PreviewAccessUnit(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val isKeyFrame: Boolean
)

interface PreviewStreamSource {
    suspend fun registerH264PreviewSubscriber(
        subscriberId: String,
        onConfig: (H264PreviewStreamConfig) -> Unit,
        onAccessUnit: (H264PreviewAccessUnit) -> Unit
    ): Boolean

    suspend fun unregisterH264PreviewSubscriber(subscriberId: String)

    fun previewStreamingDiagnostics(): PreviewStreamingDiagnostics
}
