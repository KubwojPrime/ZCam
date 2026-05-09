package com.zcam.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class AudioCuePlayback(
    val durationMs: Long
)

interface AudioCuePlayer {
    fun play(clipId: String, category: AudioPlaybackCategory): AudioCuePlayback?
    fun stop()
}

object NoOpAudioCuePlayer : AudioCuePlayer {
    override fun play(clipId: String, category: AudioPlaybackCategory): AudioCuePlayback? = null

    override fun stop() = Unit
}

@Singleton
class AndroidAudioCuePlayer @Inject constructor(
    @ApplicationContext private val context: Context
) : AudioCuePlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var activeJob: Job? = null
    private var activeToneGenerator: ToneGenerator? = null

    override fun play(clipId: String, category: AudioPlaybackCategory): AudioCuePlayback? {
        val cue = resolveCue(clipId = clipId, category = category) ?: return null
        stop()

        val generator = ToneGenerator(AudioManager.STREAM_MUSIC, cue.volume)
        synchronized(lock) {
            activeToneGenerator = generator
            activeJob = scope.launch {
                try {
                    cue.steps.forEach { step ->
                        ensureActive()
                        generator.startTone(step.tone, step.durationMs)
                        delay(step.durationMs.toLong())
                        if (step.pauseMs > 0) {
                            delay(step.pauseMs.toLong())
                        }
                    }
                } finally {
                    releaseGenerator(generator)
                }
            }
        }
        return AudioCuePlayback(durationMs = cue.totalDurationMs)
    }

    override fun stop() {
        synchronized(lock) {
            activeJob?.cancel()
            activeJob = null
            activeToneGenerator?.let { releaseGenerator(it) }
            activeToneGenerator = null
        }
    }

    private fun releaseGenerator(generator: ToneGenerator) {
        synchronized(lock) {
            if (activeToneGenerator === generator) {
                activeToneGenerator = null
            }
        }
        runCatching { generator.stopTone() }
        runCatching { generator.release() }
    }

    private fun resolveCue(
        clipId: String,
        category: AudioPlaybackCategory
    ): CueSpec? {
        return when (clipId) {
            "alert_chime" -> CueSpec(
                volume = 70,
                steps = listOf(
                    CueStep(ToneGenerator.TONE_PROP_ACK, durationMs = 130, pauseMs = 50),
                    CueStep(ToneGenerator.TONE_PROP_ACK, durationMs = 220, pauseMs = 0)
                )
            )

            "door_knock" -> CueSpec(
                volume = 80,
                steps = listOf(
                    CueStep(ToneGenerator.TONE_PROP_BEEP2, durationMs = 80, pauseMs = 70),
                    CueStep(ToneGenerator.TONE_PROP_BEEP2, durationMs = 80, pauseMs = 55),
                    CueStep(ToneGenerator.TONE_PROP_BEEP2, durationMs = 140, pauseMs = 0)
                )
            )

            "deterrent_1" -> deterrentCue()
            else -> when (category) {
                AudioPlaybackCategory.AVERSIVE -> deterrentCue()
                AudioPlaybackCategory.STANDARD -> null
            }
        }
    }

    private fun deterrentCue(): CueSpec {
        return CueSpec(
            volume = 100,
            steps = listOf(
                CueStep(ToneGenerator.TONE_PROP_NACK, durationMs = 240, pauseMs = 70),
                CueStep(ToneGenerator.TONE_PROP_NACK, durationMs = 240, pauseMs = 70),
                CueStep(ToneGenerator.TONE_SUP_ERROR, durationMs = 420, pauseMs = 0)
            )
        )
    }

    private data class CueStep(
        val tone: Int,
        val durationMs: Int,
        val pauseMs: Int
    )

    private data class CueSpec(
        val volume: Int,
        val steps: List<CueStep>
    ) {
        val totalDurationMs: Long = steps.sumOf { it.durationMs.toLong() + it.pauseMs.toLong() }
    }
}
