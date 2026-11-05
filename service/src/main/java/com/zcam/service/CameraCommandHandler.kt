package com.zcam.service

import com.zcam.core.domain.recording.CameraCommandResult
import kotlinx.coroutines.CancellationException

internal suspend fun executeCameraCommand(
    enabled: Boolean,
    promoteForeground: (Boolean) -> Unit,
    updateCamera: suspend (Boolean) -> Boolean,
    requestLocalActivation: () -> Unit
): CameraCommandResult {
    return try {
        if (enabled) promoteForeground(true)
        val applied = updateCamera(enabled)
        if (!enabled) promoteForeground(false)
        if (applied) CameraCommandResult.APPLIED else CameraCommandResult.FAILED
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        if (!enabled) return CameraCommandResult.FAILED
        requestLocalActivation()
        CameraCommandResult.LOCAL_ACTIVATION_REQUIRED
    }
}
