package com.zcam.storage

interface LoopRecordingManager {
    suspend fun start()
    suspend fun stop()
}
