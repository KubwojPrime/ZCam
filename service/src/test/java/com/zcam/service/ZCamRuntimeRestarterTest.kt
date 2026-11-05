package com.zcam.service

import com.zcam.core.domain.recording.CameraCommandResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ZCamRuntimeRestarterTest {
    @Test
    fun camera_request_returns_execution_result_instead_of_queue_acceptance() = runTest {
        val restarter = ZCamRuntimeRestarter()
        val pending = async { restarter.requestCameraEnabled(true) }
        val command = restarter.requests.first() as RuntimeControlCommand.SetCameraEnabled
        assertFalse(pending.isCompleted)
        command.result.complete(CameraCommandResult.LOCAL_ACTIVATION_REQUIRED)
        assertEquals(CameraCommandResult.LOCAL_ACTIVATION_REQUIRED, pending.await())
    }

    @Test
    fun timed_out_camera_request_is_cancelled_so_it_cannot_be_executed_later() = runTest {
        val restarter = ZCamRuntimeRestarter()
        assertEquals(CameraCommandResult.UNAVAILABLE, restarter.requestCameraEnabled(true))
        val command = restarter.requests.first() as RuntimeControlCommand.SetCameraEnabled
        assertFalse(command.result.isActive)
    }
}
