package com.zcam.camera

enum class CameraControlErrorCode {
    ENGINE_NOT_READY,
    UNSUPPORTED,
    CONFLICT,
    INTERNAL_ERROR
}

data class CameraControlsSnapshot(
    val running: Boolean,
    val torchEnabled: Boolean,
    val nightModeEnabled: Boolean,
    val lowLightBoostSupported: Boolean,
    val zoomLinear: Float = 0f,
    val zoomRatio: Float = 1f,
    val minZoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val lastError: String? = null
)

sealed interface CameraControlCommandResult {
    data class Success(
        val snapshot: CameraControlsSnapshot,
        val message: String
    ) : CameraControlCommandResult

    data class Failure(
        val code: CameraControlErrorCode,
        val message: String,
        val snapshot: CameraControlsSnapshot
    ) : CameraControlCommandResult
}

interface CameraControlManager {
    suspend fun setTorch(enabled: Boolean): CameraControlCommandResult
    suspend fun setNightMode(enabled: Boolean): CameraControlCommandResult
    suspend fun setZoomLinear(linearZoom: Float): CameraControlCommandResult
    fun controlsSnapshot(): CameraControlsSnapshot
}
