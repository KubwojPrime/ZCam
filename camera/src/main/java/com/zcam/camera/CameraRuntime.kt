package com.zcam.camera

import com.zcam.core.domain.mjpeg.MjpegStreamingEngine

interface CameraRuntime : MjpegStreamingEngine, CameraControlManager {
    suspend fun isHealthy(): Boolean
}
