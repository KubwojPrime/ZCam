package com.zcam.app

import com.zcam.core.domain.settings.RuntimeCrashState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeRestorePolicyTest {

    @Test
    fun starts_service_and_marks_recovery_when_desired_on_and_marker_dirty() {
        val plan = RuntimeRestorePolicy.plan(
            desiredShouldRun = true,
            crashState = RuntimeCrashState(runtimeDirty = true, lastCrashEpochMs = 123L)
        )

        assertTrue(plan.shouldStartService)
        assertTrue(plan.shouldMarkCrashRecovery)
        assertFalse(plan.shouldClearStaleDirtyMarker)
    }

    @Test
    fun clears_stale_marker_when_desired_off() {
        val plan = RuntimeRestorePolicy.plan(
            desiredShouldRun = false,
            crashState = RuntimeCrashState(runtimeDirty = true)
        )

        assertFalse(plan.shouldStartService)
        assertFalse(plan.shouldMarkCrashRecovery)
        assertTrue(plan.shouldClearStaleDirtyMarker)
    }
}
