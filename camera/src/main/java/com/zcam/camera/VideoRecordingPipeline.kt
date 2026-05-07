package com.zcam.camera

import java.io.File

data class VideoSegmentResult(
    val file: File,
    val success: Boolean,
    val finalizedAtEpochMs: Long,
    val bytesWritten: Long,
    val errorCode: Int?,
    val errorMessage: String?
)

interface VideoRecordingPipeline {
    suspend fun startVideoSegment(outputFile: File)
    suspend fun stopVideoSegment(timeoutMs: Long = 20_000L): VideoSegmentResult
    suspend fun abortVideoSegment()
    suspend fun isVideoSegmentActive(): Boolean
}
