package com.zcam.core.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSettingsValidatorTest {

    @Test
    fun defaults_are_valid() {
        val errors = RuntimeSettingsValidator.validate(RuntimeSettingsDefaults.value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun defaults_match_operational_requirements() {
        val defaults = RuntimeSettingsDefaults.value

        assertEquals(1280, defaults.stream.resolution.width)
        assertEquals(720, defaults.stream.resolution.height)
        assertEquals(15, defaults.stream.fps)
        assertEquals(VideoCodec.H264, defaults.stream.codec)
        assertEquals(5, defaults.recording.segmentMinutes)
        assertEquals(32, defaults.recording.maxStorageGb)
        assertEquals(5, defaults.recording.minFreeStorageGb)
    }

    @Test
    fun invalid_network_and_streaming_values_are_reported() {
        val settings = RuntimeSettingsDefaults.value.copy(
            serverPort = 99,
            stream = RuntimeSettingsDefaults.value.stream.copy(fps = 0),
            recording = RuntimeSettingsDefaults.value.recording.copy(
                segmentMinutes = 0,
                maxStorageGb = 2,
                minFreeStorageGb = 3
            )
        )

        val errors = RuntimeSettingsValidator.validate(settings)

        assertTrue(errors.any { it is RuntimeSettingsValidationError.InvalidServerPort })
        assertTrue(errors.any { it is RuntimeSettingsValidationError.InvalidFps })
        assertTrue(errors.any { it is RuntimeSettingsValidationError.InvalidSegmentMinutes })
        assertTrue(errors.any { it is RuntimeSettingsValidationError.InvalidMinFreeStorageGb })
    }

    @Test
    fun invalid_security_values_are_reported() {
        val invalidDevice = TrustedDevice(
            deviceId = "bad id with spaces",
            displayName = "",
            addedAtEpochMillis = -1L
        )
        val settings = RuntimeSettingsDefaults.value.copy(
            security = RuntimeSettingsDefaults.value.security.copy(
                pinCode = "12",
                apiToken = "bad",
                trustedDevices = setOf(invalidDevice)
            )
        )

        val errors = RuntimeSettingsValidator.validate(settings)

        assertTrue(errors.any { it is RuntimeSettingsValidationError.InvalidPin })
        assertTrue(errors.any { it is RuntimeSettingsValidationError.InvalidToken })
        assertTrue(errors.any { it is RuntimeSettingsValidationError.InvalidTrustedDevice })
    }

    @Test
    fun feature_flags_toggle_by_enum() {
        val flags = FeatureFlags().withFlag(FeatureFlag.AUDIO_PLAYBACK, enabled = false)

        assertEquals(false, flags.audioPlayback)
        assertFalse(flags.isEnabled(FeatureFlag.AUDIO_PLAYBACK))
        assertTrue(flags.isEnabled(FeatureFlag.MJPEG_STREAMING))
    }
}
