package com.zcam.core.domain.recording

data class RecordingEvent(
    val epochMs: Long,
    val confidencePercent: Int,
    val source: String,
    val type: RecordingEventType = RecordingEventType.fromSource(source)
)

enum class RecordingEventType(val wireName: String) {
    OBJECT_MOTION("object_motion"),
    CAMERA_TAMPER("camera_tamper");

    companion object {
        fun fromWireName(value: String?): RecordingEventType =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
                ?: OBJECT_MOTION

        fun fromSource(source: String): RecordingEventType =
            if (source.startsWith("camera_tamper", ignoreCase = true)) CAMERA_TAMPER else OBJECT_MOTION
    }
}

interface RecordingEventStore {
    suspend fun append(event: RecordingEvent, frameJpeg: ByteArray? = null)

    suspend fun readFrame(epochMs: Long): ByteArray?

    suspend fun query(
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
        limit: Int = 400
    ): List<RecordingEvent>

    suspend fun pruneBefore(cutoffEpochMs: Long)
}
