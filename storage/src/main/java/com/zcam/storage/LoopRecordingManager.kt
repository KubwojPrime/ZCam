package com.zcam.storage

import com.zcam.core.domain.recording.LoopRecordingEngine

interface LoopRecordingManager : LoopRecordingEngine {
    suspend fun isHealthy(): Boolean
}
