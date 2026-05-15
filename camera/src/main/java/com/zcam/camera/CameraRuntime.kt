package com.zcam.camera

import com.zcam.core.domain.mjpeg.MjpegStreamingEngine
import com.zcam.core.domain.config.EventDetectionSensitivity

interface CameraRuntime : MjpegStreamingEngine, CameraControlManager, EventDetectionControl {
    suspend fun isHealthy(): Boolean
}

interface EventDetectionControl {
    suspend fun setEventDetectionSensitivity(sensitivity: EventDetectionSensitivity)
}
