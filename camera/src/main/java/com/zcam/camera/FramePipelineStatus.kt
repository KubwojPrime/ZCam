package com.zcam.camera

data class FramePipelineStatus(
    val running: Boolean,
    val targetWidth: Int,
    val targetHeight: Int,
    val targetFps: Int,
    val producedFrames: Long,
    val droppedFrames: Long,
    val lastFrameEpochMs: Long
)

interface FramePipelineStatusSource {
    fun snapshot(): FramePipelineStatus
}
