package com.zcam.core.domain.recording

import com.zcam.core.domain.config.LoopRecordingConfig

interface LoopRecordingEngine {
    suspend fun start(config: LoopRecordingConfig)
    suspend fun stop()
    suspend fun forceRetentionSweep()
}

class StartLoopRecordingUseCase(
    private val engine: LoopRecordingEngine
) {
    suspend operator fun invoke(config: LoopRecordingConfig) {
        engine.start(config)
    }
}

class StopLoopRecordingUseCase(
    private val engine: LoopRecordingEngine
) {
    suspend operator fun invoke() {
        engine.stop()
    }
}
