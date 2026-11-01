package com.zcam.camera

import com.zcam.core.domain.recording.RecordingEventType
import com.zcam.core.logging.ZCamLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneEventDetectorTest {

    @Test
    fun localized_change_is_reported_as_object_motion() {
        val detector = SceneEventDetector(NoopLogger())
        val background = patternedFrame()
        warmUp(detector, background)

        val withObject = mutate(background) { x, y, value ->
            if (x in 24..37 && y in 16..28) (value + 80).coerceAtMost(255) else value
        }

        assertNull(detector.analyze(withObject, 20_000L))
        val event = detector.analyze(withObject, 20_100L)

        assertEquals(RecordingEventType.OBJECT_MOTION, event?.type)
        assertTrue(event?.source?.startsWith("object_motion:") == true)
    }

    @Test
    fun covering_camera_is_reported_as_tamper() {
        val detector = SceneEventDetector(NoopLogger())
        warmUp(detector, patternedFrame())
        val covered = LumaFrame(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { 4 })

        assertNull(detector.analyze(covered, 30_000L))
        val event = detector.analyze(covered, 30_100L)

        assertEquals(RecordingEventType.CAMERA_TAMPER, event?.type)
        assertEquals("camera_tamper:covered", event?.source)
    }

    @Test
    fun global_scene_shift_is_not_reported_as_object_motion() {
        val detector = SceneEventDetector(NoopLogger())
        val background = patternedFrame()
        warmUp(detector, background)

        val shifted = shift(background, 7)
        assertNull(detector.analyze(shifted, 40_000L))
        val event = detector.analyze(shifted, 40_100L)

        assertEquals(RecordingEventType.CAMERA_TAMPER, event?.type)
        assertEquals("camera_tamper:moved", event?.source)
    }

    private fun warmUp(detector: SceneEventDetector, frame: LumaFrame) {
        repeat(10) { index -> detector.analyze(frame, 1_000L + index * 100L) }
    }

    private fun patternedFrame(): LumaFrame = LumaFrame(
        WIDTH,
        HEIGHT,
        IntArray(WIDTH * HEIGHT) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            35 + ((x * 17 + y * 31 + x * y * 3) % 185)
        }
    )

    private fun mutate(frame: LumaFrame, transform: (Int, Int, Int) -> Int): LumaFrame =
        frame.copy(pixels = IntArray(frame.pixels.size) { index ->
            transform(index % frame.width, index / frame.width, frame.pixels[index])
        })

    private fun shift(frame: LumaFrame, distance: Int): LumaFrame =
        frame.copy(pixels = IntArray(frame.pixels.size) { index ->
            val x = index % frame.width
            val y = index / frame.width
            frame.pixels[y * frame.width + (x + distance) % frame.width]
        })

    private class NoopLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }

    private companion object {
        const val WIDTH = 80
        const val HEIGHT = 45
    }
}
