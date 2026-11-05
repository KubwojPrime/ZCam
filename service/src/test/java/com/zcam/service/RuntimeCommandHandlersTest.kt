package com.zcam.service

import com.zcam.core.domain.recording.CameraCommandResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RuntimeCommandHandlersTest {
    @Test
    fun denied_camera_promotion_requests_local_activation_without_changing_camera_state() = runTest {
        var updated = false
        var notified = false
        val result = executeCameraCommand(true,
            promoteForeground = { throw SecurityException("while-in-use permission unavailable") },
            updateCamera = { updated = true; true },
            requestLocalActivation = { notified = true })
        assertEquals(CameraCommandResult.LOCAL_ACTIVATION_REQUIRED, result)
        assertFalse(updated)
        assertTrue(notified)
    }

    @Test
    fun failed_camera_start_is_not_reported_as_applied() = runTest {
        val result = executeCameraCommand(true, {}, { false }, { fail("No permission failure") })
        assertEquals(CameraCommandResult.FAILED, result)
    }

    @Test
    fun camera_off_stops_capture_before_changing_foreground_types() = runTest {
        val order = mutableListOf<String>()
        val result = executeCameraCommand(false,
            promoteForeground = { assertFalse(it); order.add("foreground") },
            updateCamera = { assertFalse(it); order.add("camera"); true },
            requestLocalActivation = { fail("OFF needs no local activation") })
        assertEquals(CameraCommandResult.APPLIED, result)
        assertEquals(listOf("camera", "foreground"), order)
    }

    @Test
    fun denied_recovery_requests_user_action_and_does_not_report_success() {
        val denied = IllegalStateException("background start denied")
        var notified = false
        assertFalse(executeServerRecovery({ throw denied }, { it === denied }, { notified = true }))
        assertTrue(notified)
    }

    @Test
    fun successful_recovery_does_not_request_user_action() {
        assertTrue(executeServerRecovery({}, { false }, { fail("Server started") }))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unexpected_recovery_failure_is_not_masked_as_a_background_restriction() {
        executeServerRecovery({ throw IllegalArgumentException("invalid configuration") }, { false }, {})
    }
}
