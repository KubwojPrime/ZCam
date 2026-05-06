package com.zcam.core.domain.config

import com.zcam.core.domain.settings.AllowlistFeatureFlagGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagGuardTest {

    @Test
    fun allowlist_guard_blocks_sensitive_flags_by_default() {
        val guard = AllowlistFeatureFlagGuard(AllowlistFeatureFlagGuard.defaultMutableFlags)

        assertTrue(guard.canUpdate(FeatureFlag.MJPEG_STREAMING))
        assertTrue(guard.canUpdate(FeatureFlag.WATCHDOG_RECOVERY))
        assertFalse(guard.canUpdate(FeatureFlag.TRUSTED_DEVICES))
    }
}
