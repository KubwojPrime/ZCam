package com.zcam.app

import com.zcam.core.domain.settings.RuntimeCrashState

data class RuntimeRestorePlan(
    val shouldStartService: Boolean,
    val shouldMarkCrashRecovery: Boolean,
    val shouldClearStaleDirtyMarker: Boolean
)

object RuntimeRestorePolicy {

    fun plan(
        desiredShouldRun: Boolean,
        crashState: RuntimeCrashState
    ): RuntimeRestorePlan {
        if (desiredShouldRun) {
            return RuntimeRestorePlan(
                shouldStartService = true,
                shouldMarkCrashRecovery = crashState.runtimeDirty,
                shouldClearStaleDirtyMarker = false
            )
        }
        return RuntimeRestorePlan(
            shouldStartService = false,
            shouldMarkCrashRecovery = false,
            shouldClearStaleDirtyMarker = crashState.runtimeDirty
        )
    }
}
