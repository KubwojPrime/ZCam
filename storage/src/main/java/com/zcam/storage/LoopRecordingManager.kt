package com.zcam.storage

import com.zcam.core.domain.recording.LoopRecordingEngine
import java.io.File

interface LoopRecordingManager : LoopRecordingEngine {
    suspend fun isHealthy(): Boolean

    suspend fun queryRecordings(
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
        limit: Int = 200
    ): List<RecordingClipSummary>

    suspend fun queryRecordingEvents(
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
        limit: Int = 400
    ): List<RecordingEventSummary>

    suspend fun readEventFrame(epochMs: Long): ByteArray? = null

    suspend fun resolveRecordingFile(fileName: String): File?
}

data class RecordingClipSummary(
    val fileName: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val sizeBytes: Long,
    val container: String,
    val codec: String
) {
    val durationMs: Long = (endedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
}

data class RecordingEventSummary(
    val epochMs: Long,
    val endedAtEpochMs: Long = epochMs,
    val confidencePercent: Int,
    val source: String,
    val type: String = "object_motion",
    val recordingFileName: String?,
    val recordingStartedAtEpochMs: Long? = null,
    val recordingEndedAtEpochMs: Long? = null,
    val recordingOffsetMs: Long? = null
) {
    val durationMs: Long = (endedAtEpochMs - epochMs).coerceAtLeast(0L)
}
