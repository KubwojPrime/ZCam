package com.zcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.SizeF
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlinx.coroutines.suspendCancellableCoroutine

data class RearCameraLensCatalog(
    val mainCameraId: String? = null,
    val ultraWideCameraId: String? = null
) {
    val ultraWideAvailable: Boolean
        get() = !ultraWideCameraId.isNullOrBlank()
}

object RearCameraLensDetector {

    suspend fun detectCatalog(context: Context): RearCameraLensCatalog {
        val provider = awaitCameraProvider(context)
        return detectCatalog(provider)
    }

    fun detectCatalog(provider: ProcessCameraProvider): RearCameraLensCatalog {
        val cameraInfos = provider.availableCameraInfos
        val rearCandidates = cameraInfos
            .mapNotNull(::cameraCandidateOrNull)
            .filter { candidate -> candidate.isRearFacing }

        if (rearCandidates.isEmpty()) {
            return RearCameraLensCatalog()
        }

        val defaultBackId = runCatching {
            CameraSelector.DEFAULT_BACK_CAMERA
                .filter(cameraInfos)
                .firstOrNull()
                ?.let { info -> Camera2CameraInfo.from(info).cameraId }
        }.getOrNull()

        val mainCandidate = rearCandidates.firstOrNull { candidate -> candidate.cameraId == defaultBackId }
            ?: rearCandidates.maxByOrNull(RearCameraCandidate::wideScore)
            ?: return RearCameraLensCatalog()

        val ultraWideCandidate = rearCandidates
            .asSequence()
            .filter { candidate -> candidate.cameraId != mainCandidate.cameraId }
            .maxByOrNull(RearCameraCandidate::wideScore)
            ?.takeIf { candidate ->
                candidate.wideScore > mainCandidate.wideScore * ULTRA_WIDE_THRESHOLD_MULTIPLIER
            }

        return RearCameraLensCatalog(
            mainCameraId = mainCandidate.cameraId,
            ultraWideCameraId = ultraWideCandidate?.cameraId
        )
    }

    private fun cameraCandidateOrNull(cameraInfo: CameraInfo): RearCameraCandidate? {
        val info = Camera2CameraInfo.from(cameraInfo)
        val lensFacing = info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
        val cameraId = runCatching { info.cameraId }.getOrNull() ?: return null
        val focalLength = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
            ?.takeIf { value -> value > 0f }
            ?: return null
        val sensorSize = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val wideScore = wideScore(sensorSize = sensorSize, focalLengthMm = focalLength)
        return RearCameraCandidate(
            cameraId = cameraId,
            isRearFacing = lensFacing == CameraCharacteristics.LENS_FACING_BACK,
            wideScore = wideScore
        )
    }

    private fun wideScore(sensorSize: SizeF?, focalLengthMm: Float): Double {
        if (focalLengthMm <= 0f) return 0.0
        val sensorDiagonalMm = sensorSize?.let { size ->
            hypot(size.width.toDouble(), size.height.toDouble())
        }
        return if (sensorDiagonalMm != null && sensorDiagonalMm > 0.0) {
            sensorDiagonalMm / focalLengthMm.toDouble()
        } else {
            1.0 / focalLengthMm.toDouble()
        }
    }

    private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider {
        return suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { provider -> continuation.resume(provider) }
                        .onFailure { error -> continuation.resumeWithException(error) }
                },
                ContextCompat.getMainExecutor(context)
            )
            continuation.invokeOnCancellation {
                future.cancel(true)
            }
        }
    }

    private data class RearCameraCandidate(
        val cameraId: String,
        val isRearFacing: Boolean,
        val wideScore: Double
    )

    private const val ULTRA_WIDE_THRESHOLD_MULTIPLIER = 1.18
}
