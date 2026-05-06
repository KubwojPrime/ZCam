package com.zcam.watchdog

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.watchdog.RecoveryReason
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessWatchdogManagerRecoveryTest {

    @Test
    fun emits_recovery_event_for_stale_component() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = ProcessWatchdogManager(
            dispatchers = object : DispatcherProvider {
                override val io = dispatcher
                override val default = dispatcher
            },
            logger = object : ZCamLogger {
                override fun d(message: String) = Unit
                override fun i(message: String) = Unit
                override fun w(message: String) = Unit
                override fun e(throwable: Throwable?, message: String) = Unit
            }
        )

        val events = mutableListOf<com.zcam.core.domain.watchdog.RecoveryRequest>()
        val collector = launch {
            manager.recoveryEvents.collect { events += it }
        }

        manager.start()
        manager.registerComponent("camera", timeoutMs = 500)
        manager.heartbeat("camera")

        advanceTimeBy(2_500)
        runCurrent()

        assertTrue(events.any { it.component == "camera" && it.reason == RecoveryReason.STALE_HEARTBEAT })
        assertEquals(
            com.zcam.core.domain.watchdog.WatchdogComponentStatus.STALE,
            manager.health.value.components.getValue("camera").status
        )

        collector.cancel()
        manager.stop()
    }
}
