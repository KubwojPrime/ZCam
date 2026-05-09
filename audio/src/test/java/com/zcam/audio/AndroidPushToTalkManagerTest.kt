package com.zcam.audio

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPushToTalkManagerTest {

    private val dispatcher = StandardTestDispatcher()

    @Test
    fun mapped_standard_quick_sound_triggers_cue_and_clears_playback_state() = runTest(dispatcher) {
        val cuePlayer = RecordingCuePlayer(durationMs = 250L)
        val manager = createManager(cuePlayer = cuePlayer)

        manager.start()
        val result = manager.playStoredAudio(
            AudioPlaybackRequest(
                clipId = "alert_chime",
                category = AudioPlaybackCategory.STANDARD
            )
        )

        assertTrue(result is AudioCommandResult.Success)
        assertEquals("alert_chime", cuePlayer.lastClipId)
        assertEquals(AudioPlaybackCategory.STANDARD, cuePlayer.lastCategory)
        assertTrue(manager.snapshotState().playingBack)

        advanceTimeBy(251L)

        assertFalse(manager.snapshotState().playingBack)
        assertNull(manager.snapshotState().activeClipId)
    }

    @Test
    fun aversive_unknown_clip_uses_fallback_cue_and_clears_state() = runTest(dispatcher) {
        val cuePlayer = RecordingCuePlayer(durationMs = 300L)
        val manager = createManager(cuePlayer = cuePlayer)

        manager.start()
        val result = manager.playStoredAudio(
            AudioPlaybackRequest(
                clipId = "alarm_01",
                category = AudioPlaybackCategory.AVERSIVE
            )
        )

        assertTrue(result is AudioCommandResult.Success)
        assertEquals("alarm_01", cuePlayer.lastClipId)
        assertEquals(AudioPlaybackCategory.AVERSIVE, cuePlayer.lastCategory)

        advanceTimeBy(301L)

        assertFalse(manager.snapshotState().playingBack)
        assertNull(manager.snapshotState().activeClipId)
    }

    private fun createManager(cuePlayer: AudioCuePlayer): AndroidPushToTalkManager {
        return AndroidPushToTalkManager(
            dispatchers = object : DispatcherProvider {
                override val io: CoroutineDispatcher = dispatcher
                override val default: CoroutineDispatcher = dispatcher
            },
            logger = NoopLogger(),
            systemVolumeController = FakeSystemVolumeController(),
            audioCuePlayer = cuePlayer
        )
    }

    private class RecordingCuePlayer(
        private val durationMs: Long
    ) : AudioCuePlayer {
        var lastClipId: String? = null
        var lastCategory: AudioPlaybackCategory? = null

        override fun play(
            clipId: String,
            category: AudioPlaybackCategory
        ): AudioCuePlayback {
            lastClipId = clipId
            lastCategory = category
            return AudioCuePlayback(durationMs = durationMs)
        }

        override fun stop() = Unit
    }

    private class FakeSystemVolumeController : SystemVolumeController {
        override fun currentMusicVolumePercent(): Int? = 40

        override fun setMusicVolumePercent(levelPercent: Int): SystemVolumeApplyResult {
            return SystemVolumeApplyResult(
                applied = true,
                actualPercent = levelPercent,
                streamVolume = levelPercent,
                streamMaxVolume = 100
            )
        }
    }

    private class NoopLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
