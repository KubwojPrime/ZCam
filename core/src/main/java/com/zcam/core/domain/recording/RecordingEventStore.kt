package com.zcam.core.domain.recording

data class RecordingEvent(
    val epochMs: Long,
    val confidencePercent: Int,
    val source: String
)

interface RecordingEventStore {
    suspend fun append(event: RecordingEvent)

    suspend fun query(
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
        limit: Int = 400
    ): List<RecordingEvent>

    suspend fun pruneBefore(cutoffEpochMs: Long)
}
