package com.zcam.camera

import com.zcam.core.domain.mjpeg.MjpegStreamingEngine

interface CameraRuntime : MjpegStreamingEngine {
    suspend fun isHealthy(): Boolean
}
