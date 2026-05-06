package com.zcam.camera

interface CameraRuntime {
    suspend fun start()
    suspend fun stop()
}
