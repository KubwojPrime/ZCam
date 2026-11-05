package com.zcam.core.domain.recording

interface RuntimeRestartRequester {
    fun requestRestart(): Boolean

    suspend fun requestCameraEnabled(enabled: Boolean): CameraCommandResult
}

enum class CameraCommandResult(val reason: String) {
    APPLIED(""),
    LOCAL_ACTIVATION_REQUIRED("local_camera_activation_required"),
    UNAVAILABLE("camera_control_unavailable"),
    FAILED("camera_update_failed")
}
