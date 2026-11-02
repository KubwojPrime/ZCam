package com.zcam.storage

import com.zcam.core.domain.recording.RecordingEvent
import com.zcam.core.domain.recording.RecordingEventType
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingEventCoalescingTest {

    @Test
    fun consecutive_hits_become_one_timed_event() {
        val result = coalesceRecordingEvents(
            listOf(
                event(at = 10_000L, confidence = 40),
                event(at = 25_000L, confidence = 75),
                event(at = 50_000L, confidence = 60)
            )
        )

        assertEquals(1, result.size)
        assertEquals(10_000L, result.single().epochMs)
        assertEquals(50_000L, result.single().endedAtEpochMs)
        assertEquals(40_000L, result.single().durationMs)
        assertEquals(75, result.single().confidencePercent)
    }

    @Test
    fun different_type_or_long_quiet_period_starts_new_event() {
        val result = coalesceRecordingEvents(
            listOf(
                event(at = 10_000L),
                event(at = 20_000L, type = RecordingEventType.CAMERA_TAMPER),
                event(at = 60_001L, type = RecordingEventType.CAMERA_TAMPER)
            )
        )

        assertEquals(3, result.size)
    }

    private fun event(
        at: Long,
        confidence: Int = 50,
        type: RecordingEventType = RecordingEventType.OBJECT_MOTION
    ) = RecordingEvent(
        epochMs = at,
        confidencePercent = confidence,
        source = type.wireName,
        type = type
    )
}
