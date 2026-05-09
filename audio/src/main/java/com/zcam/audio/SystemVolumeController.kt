package com.zcam.audio

import android.content.Context
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class SystemVolumeApplyResult(
    val applied: Boolean,
    val actualPercent: Int?,
    val streamVolume: Int?,
    val streamMaxVolume: Int?,
    val reason: String? = null
)

interface SystemVolumeController {
    fun currentMusicVolumePercent(): Int?
    fun setMusicVolumePercent(levelPercent: Int): SystemVolumeApplyResult
}

@Singleton
class AndroidSystemVolumeController @Inject constructor(
    @ApplicationContext private val context: Context
) : SystemVolumeController {

    override fun currentMusicVolumePercent(): Int? {
        val audioManager = audioManager() ?: return null
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) return null
        val minVolume = minStreamVolume(audioManager)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return streamVolumeToPercent(
            streamVolume = current,
            minVolume = minVolume,
            maxVolume = maxVolume
        )
    }

    override fun setMusicVolumePercent(levelPercent: Int): SystemVolumeApplyResult {
        val audioManager = audioManager()
            ?: return SystemVolumeApplyResult(
                applied = false,
                actualPercent = null,
                streamVolume = null,
                streamMaxVolume = null,
                reason = "audio manager unavailable"
            )

        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (maxVolume <= 0) {
            return SystemVolumeApplyResult(
                applied = false,
                actualPercent = null,
                streamVolume = null,
                streamMaxVolume = maxVolume,
                reason = "music stream volume range unavailable"
            )
        }

        val minVolume = minStreamVolume(audioManager)
        val targetVolume = percentToStreamVolume(
            levelPercent = levelPercent.coerceIn(0, 100),
            minVolume = minVolume,
            maxVolume = maxVolume
        )

        return runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            val actualVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            SystemVolumeApplyResult(
                applied = true,
                actualPercent = streamVolumeToPercent(
                    streamVolume = actualVolume,
                    minVolume = minVolume,
                    maxVolume = maxVolume
                ),
                streamVolume = actualVolume,
                streamMaxVolume = maxVolume
            )
        }.getOrElse { error ->
            SystemVolumeApplyResult(
                applied = false,
                actualPercent = currentMusicVolumePercent(),
                streamVolume = null,
                streamMaxVolume = maxVolume,
                reason = error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun audioManager(): AudioManager? {
        return context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private fun minStreamVolume(audioManager: AudioManager): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }
    }

    private fun percentToStreamVolume(
        levelPercent: Int,
        minVolume: Int,
        maxVolume: Int
    ): Int {
        val range = (maxVolume - minVolume).coerceAtLeast(0)
        if (range == 0) return minVolume.coerceAtLeast(0)
        return (minVolume + (range * (levelPercent / 100f)))
            .roundToInt()
            .coerceIn(minVolume, maxVolume)
    }

    private fun streamVolumeToPercent(
        streamVolume: Int,
        minVolume: Int,
        maxVolume: Int
    ): Int {
        val range = (maxVolume - minVolume).coerceAtLeast(0)
        if (range == 0) return 0
        return (((streamVolume - minVolume) * 100f) / range)
            .roundToInt()
            .coerceIn(0, 100)
    }
}
