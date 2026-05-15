package com.zcam.client

import com.zcam.core.domain.config.PreviewTransport
import com.zcam.core.domain.config.RearCameraLens
import com.zcam.core.domain.config.EventDetectionSensitivity
import java.io.OutputStream

data class ClientTarget(
    val host: String,
    val port: Int,
    val token: String? = null,
    val deviceId: String? = null
)

data class ClientServerStatus(
    val alive: Boolean,
    val overallStatus: String,
    val streamClients: Int,
    val uptimeMs: Long,
    val videoRunning: Boolean,
    val lastFrameAgeMs: Long,
    val previewTransport: PreviewTransport,
    val previewTargetWidth: Int,
    val previewTargetHeight: Int,
    val previewActualWidth: Int,
    val previewActualHeight: Int,
    val previewTargetFps: Int,
    val previewTargetBitrateKbps: Int,
    val previewEstimatedBitrateKbps: Int,
    val previewSentFps: Int,
    val previewSubscriberCount: Int,
    val previewEncoderRunning: Boolean,
    val previewMjpegFallbackAvailable: Boolean,
    val previewDroppedFrames: Long,
    val previewEncoderError: String?,
    val torchEnabled: Boolean,
    val nightModeEnabled: Boolean,
    val lowLightBoostSupported: Boolean,
    val zoomLinear: Float,
    val zoomRatio: Float,
    val minZoomRatio: Float,
    val maxZoomRatio: Float,
    val selectedRearLens: RearCameraLens,
    val activeRearLens: RearCameraLens,
    val ultraWideAvailable: Boolean,
    val eventSensitivity: EventDetectionSensitivity,
    val audioTransmitting: Boolean,
    val audioLiveListening: Boolean,
    val audioPlayingBack: Boolean,
    val audioVolumePercent: Int?,
    val audioMinVolumePercent: Int?,
    val audioMaxVolumePercent: Int?,
    val batteryPercent: Int?,
    val charging: Boolean?
)

data class ClientPairingQr(
    val sessionId: String,
    val pairingCode: String,
    val qrPayload: String,
    val expiresAtEpochMs: Long
)

data class ClientPairingRequest(
    val requestId: String,
    val deviceId: String,
    val displayName: String,
    val expiresAtEpochMs: Long
)

data class ClientPairingResult(
    val tokenId: String,
    val tokenValue: String,
    val deviceId: String
)

data class ClientRecordingSummary(
    val fileName: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val container: String,
    val codec: String
)

data class ClientRecordingEvent(
    val epochMs: Long,
    val confidencePercent: Int,
    val source: String,
    val recordingFileName: String?,
    val recordingStartedAtEpochMs: Long?,
    val recordingEndedAtEpochMs: Long?,
    val recordingOffsetMs: Long?
)

sealed interface ClientCallResult<out T> {
    data class Success<T>(val value: T) : ClientCallResult<T>

    data class Failure(
        val code: Int?,
        val reason: String,
        val responseBody: String? = null
    ) : ClientCallResult<Nothing>
}

interface LocalClient {
    suspend fun isServerAlive(target: ClientTarget): Boolean
    suspend fun fetchStatus(target: ClientTarget): ClientCallResult<ClientServerStatus>
    suspend fun fetchSnapshot(target: ClientTarget): ClientCallResult<ByteArray>
    fun buildPreviewStreamUrl(target: ClientTarget): String
    fun buildPreviewH264SocketUrl(target: ClientTarget): String
    suspend fun setPushToTalk(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit>
    suspend fun setLiveListen(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit>
    suspend fun setTorch(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit>
    suspend fun setNightMode(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit>
    suspend fun setZoomLinear(target: ClientTarget, linearZoom: Float): ClientCallResult<Unit>
    suspend fun playQuickSound(
        target: ClientTarget,
        clipId: String,
        aversive: Boolean
    ): ClientCallResult<Unit>

    suspend fun setVolume(target: ClientTarget, levelPercent: Int): ClientCallResult<Unit>
    suspend fun fetchPairingQr(target: ClientTarget): ClientCallResult<ClientPairingQr>
    suspend fun requestPairing(
        target: ClientTarget,
        deviceId: String,
        displayName: String,
        clientType: String
    ): ClientCallResult<ClientPairingRequest>

    suspend fun completePairingRequest(
        target: ClientTarget,
        requestId: String,
        verificationCode: String
    ): ClientCallResult<ClientPairingResult>

    suspend fun pairDevice(
        target: ClientTarget,
        pin: String,
        sessionId: String,
        pairingCode: String,
        deviceId: String,
        displayName: String
    ): ClientCallResult<ClientPairingResult>

    suspend fun fetchRecordings(
        target: ClientTarget,
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
        limit: Int = 120
    ): ClientCallResult<List<ClientRecordingSummary>>

    suspend fun fetchRecordingEvents(
        target: ClientTarget,
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
        limit: Int = 400
    ): ClientCallResult<List<ClientRecordingEvent>>

    fun buildRecordingPlaybackUrl(target: ClientTarget, fileName: String): String

    suspend fun downloadRecording(
        target: ClientTarget,
        fileName: String,
        destination: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> }
    ): ClientCallResult<Unit>
}
