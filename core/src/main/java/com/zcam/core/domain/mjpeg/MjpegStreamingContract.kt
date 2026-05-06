package com.zcam.core.domain.mjpeg

import com.zcam.core.domain.config.StreamConfig

interface MjpegStreamingEngine {
    suspend fun start(config: StreamConfig)
    suspend fun stop()
    fun latestFrame(): ByteArray
}

class StartMjpegStreamingUseCase(
    private val engine: MjpegStreamingEngine
) {
    suspend operator fun invoke(config: StreamConfig) {
        engine.start(config)
    }
}

class StopMjpegStreamingUseCase(
    private val engine: MjpegStreamingEngine
) {
    suspend operator fun invoke() {
        engine.stop()
    }
}
