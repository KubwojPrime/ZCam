package com.zcam.service.runtime

import com.zcam.audio.PushToTalkManager
import com.zcam.camera.CameraRuntime
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.FeatureFlags
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.RuntimeDesiredState
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.domain.watchdog.RecoveryRequest
import com.zcam.core.domain.watchdog.WatchdogComponentHealth
import com.zcam.core.domain.watchdog.WatchdogComponentStatus
import com.zcam.core.domain.watchdog.WatchdogHealthSnapshot
import com.zcam.core.logging.ZCamLogger
import com.zcam.server.LocalHttpServer
import com.zcam.service.ZCamRuntimeCoordinator
import com.zcam.storage.LoopRecordingManager
import com.zcam.watchdog.WatchdogManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZCamRuntimeCoordinatorRecoveryTest {

    @Test
    fun recovers_failed_component_with_retry_backoff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)

        val flakyServer = FlakyServer(failuresBeforeSuccess = 2)
        val watchdog = FakeWatchdogManager()
        val runtimeState = FakeRuntimeStateRepository()
        val runtimeSettings = FakeRuntimeSettingsRepository(
            RuntimeSettingsDefaults.value.copy(
                featureFlags = FeatureFlags(
                    mjpegStreaming = false,
                    loopRecording = false,
                    audioPushToTalk = false,
                    audioLive = false,
                    audioPlayback = false,
                    trustedDevices = true,
                    watchdogRecovery = true
                )
            )
        )
        val scheduler = RecordingBackoffScheduler()
        val coordinator = ZCamRuntimeCoordinator(
            cameraRuntime = NoopCameraRuntime(),
            localHttpServer = flakyServer,
            pushToTalkManager = NoopAudioManager(),
            loopRecordingManager = NoopLoopRecordingManager(),
            watchdogManager = watchdog,
            runtimeSettingsRepository = runtimeSettings,
            runtimeStateRepository = runtimeState,
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = scheduler,
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        advanceTimeBy(50)

        assertTrue(flakyServer.startCalls >= 3)
        assertTrue(scheduler.recordedDelays.any { it >= 5 })
        assertEquals(true, runtimeState.desiredState.value.shouldRun)
        assertEquals(
            ComponentHealthStatus.HEALTHY,
            coordinator.health.value.components.getValue(RuntimeComponent.SERVER).status
        )

        coordinator.stop(persistDesiredState = true)
        advanceUntilIdle()

        assertEquals(false, runtimeState.desiredState.value.shouldRun)
    }

    private class TestDispatcherProvider(
        private val dispatcher: CoroutineDispatcher
    ) : DispatcherProvider {
        override val io = dispatcher
        override val default = dispatcher
    }

    private class FlakyServer(
        private var failuresBeforeSuccess: Int
    ) : LocalHttpServer {
        var startCalls: Int = 0
            private set
        private var healthy: Boolean = false

        override suspend fun start(port: Int) {
            startCalls += 1
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess -= 1
                throw IllegalStateException("simulated startup failure")
            }
            healthy = true
        }

        override suspend fun stop() {
            healthy = false
        }

        override suspend fun isHealthy(): Boolean = healthy
    }

    private class FakeWatchdogManager : WatchdogManager {
        private val _health = MutableStateFlow(
            WatchdogHealthSnapshot(
                started = false,
                generatedAtEpochMs = 0L,
                components = emptyMap()
            )
        )
        private val _events = MutableSharedFlow<RecoveryRequest>(extraBufferCapacity = 16)

        override val health: StateFlow<WatchdogHealthSnapshot> = _health.asStateFlow()
        override val recoveryEvents: Flow<RecoveryRequest> = _events.asSharedFlow()

        override suspend fun start() {
            _health.value = _health.value.copy(started = true)
        }

        override suspend fun stop() {
            _health.value = _health.value.copy(started = false)
        }

        override suspend fun registerComponent(component: String, timeoutMs: Long) {
            _health.value = _health.value.copy(
                components = _health.value.components + (
                    component to WatchdogComponentHealth(
                        component = component,
                        heartbeatTimeoutMs = timeoutMs,
                        status = WatchdogComponentStatus.STARTING
                    )
                    )
            )
        }

        override suspend fun updateComponentStatus(
            component: String,
            status: WatchdogComponentStatus,
            details: String?
        ) {
            val existing = _health.value.components[component] ?: WatchdogComponentHealth(component = component)
            _health.value = _health.value.copy(
                components = _health.value.components + (
                    component to existing.copy(
                        status = status,
                        lastDetails = details
                    )
                    )
            )
        }

        override suspend fun heartbeat(component: String) {
            // no-op
        }

        override suspend fun requestRecovery(request: RecoveryRequest) {
            _events.emit(request)
        }
    }

    private class RecordingBackoffScheduler : RetryBackoffScheduler {
        val recordedDelays = mutableListOf<Long>()

        override suspend fun pause(millis: Long) {
            recordedDelays += millis
            delay(1)
        }
    }

    private class FakeRuntimeStateRepository : RuntimeStateRepository {
        override val desiredState = MutableStateFlow(
            RuntimeDesiredState(
                shouldRun = false,
                lastChangedAtEpochMs = 0L
            )
        )

        override suspend fun setDesiredRunning(shouldRun: Boolean) {
            desiredState.value = RuntimeDesiredState(shouldRun, System.currentTimeMillis())
        }
    }

    private class FakeRuntimeSettingsRepository(
        initial: RuntimeSettings
    ) : RuntimeSettingsRepository {
        override val settings = MutableStateFlow(initial)

        override suspend fun updateSettings(candidate: RuntimeSettings): RuntimeSettingsUpdateResult {
            settings.value = candidate
            return RuntimeSettingsUpdateResult.Success(candidate)
        }

        override suspend fun setFeatureFlag(
            flag: com.zcam.core.domain.config.FeatureFlag,
            enabled: Boolean
        ): RuntimeSettingsUpdateResult {
            val next = settings.value.copy(featureFlags = settings.value.featureFlags.withFlag(flag, enabled))
            settings.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }

        override suspend fun upsertTrustedDevice(device: TrustedDevice): RuntimeSettingsUpdateResult {
            val nextDevices = settings.value.security.trustedDevices
                .filterNot { it.deviceId == device.deviceId }
                .toSet() + device
            val next = settings.value.copy(security = settings.value.security.copy(trustedDevices = nextDevices))
            settings.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }

        override suspend fun removeTrustedDevice(deviceId: String): RuntimeSettingsUpdateResult {
            val next = settings.value.copy(
                security = settings.value.security.copy(
                    trustedDevices = settings.value.security.trustedDevices.filterNot { it.deviceId == deviceId }.toSet()
                )
            )
            settings.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }
    }

    private class NoopCameraRuntime : CameraRuntime {
        override suspend fun start(config: com.zcam.core.domain.config.StreamConfig) {
            // no-op
        }

        override suspend fun stop() {
            // no-op
        }

        override suspend fun isHealthy(): Boolean = true

        override fun latestFrame(): ByteArray = byteArrayOf(0)
    }

    private class NoopAudioManager : PushToTalkManager {
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun isHealthy() = true
        override suspend fun beginPushToTalk() = Unit
        override suspend fun endPushToTalk() = Unit
        override suspend fun beginLiveListen() = Unit
        override suspend fun stopLiveListen() = Unit
        override suspend fun startPlayback(source: com.zcam.core.domain.audio.PlaybackSource) = Unit
        override suspend fun stopPlayback() = Unit
    }

    private class NoopLoopRecordingManager : LoopRecordingManager {
        override suspend fun start(config: com.zcam.core.domain.config.LoopRecordingConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun isHealthy() = true
        override suspend fun forceRetentionSweep() = Unit
    }

    private class NoopLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
