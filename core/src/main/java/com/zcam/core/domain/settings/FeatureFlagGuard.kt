package com.zcam.core.domain.settings

import com.zcam.core.domain.config.FeatureFlag

interface FeatureFlagGuard {
    fun canUpdate(flag: FeatureFlag): Boolean
}

class AllowlistFeatureFlagGuard(
    private val mutableFlags: Set<FeatureFlag>
) : FeatureFlagGuard {

    override fun canUpdate(flag: FeatureFlag): Boolean = flag in mutableFlags

    companion object {
        val defaultMutableFlags: Set<FeatureFlag> = setOf(
            FeatureFlag.MJPEG_STREAMING,
            FeatureFlag.LOOP_RECORDING,
            FeatureFlag.AUDIO_PUSH_TO_TALK,
            FeatureFlag.AUDIO_LIVE,
            FeatureFlag.AUDIO_PLAYBACK,
            FeatureFlag.WATCHDOG_RECOVERY
        )
    }
}
