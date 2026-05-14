package com.zcam.service.runtime

import com.zcam.audio.PushToTalkManager
import com.zcam.audio.AudioTransportConfig
import com.zcam.camera.CameraRuntime
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.FeatureFlags
import com.zcam.core.domain.config.RearCameraLens
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.RuntimeDesiredState
import com.zcam.core.domain.settings.RuntimeCrashRepository
import com.zcam.core.domain.settings.RuntimeCrashState
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
import com.zcam.storage.RecordingClipSummary
import com.zcam.storage.RecordingEventSummary
import com.zcam.watchdog.WatchdogManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

class ZCamRuntimeCoordinatorRecoveryTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recovers_failed_component_with_retry_backoff() = runTest(timeout = 20.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)

        val flakyServer = FlakyServer(failuresBeforeSuccess = 2)
        val watchdog = FakeWatchdogManager()
        val runtimeState = FakeRuntimeStateRepository()
        val runtimeCrash = FakeRuntimeCrashRepository()
        val environmentMonitor = FakeRuntimeEnvironmentMonitor()
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
            runtimeEnvironmentMonitor = environmentMonitor,
            runtimeSettingsRepository = runtimeSettings,
            runtimeCrashRepository = runtimeCrash,
            runtimeStateRepository = runtimeState,
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = scheduler,
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        try {
            advanceTimeBy(200)

            assertTrue(flakyServer.startCalls >= 3)
            assertTrue(scheduler.recordedDelays.any { it >= 5 })
            assertEquals(true, runtimeState.desiredState.value.shouldRun)
            assertEquals(
                ComponentHealthStatus.HEALTHY,
                coordinator.health.value.components.getValue(RuntimeComponent.SERVER).status
            )
        } finally {
            coordinator.stop(persistDesiredState = true)
            advanceUntilIdle()
        }

        assertEquals(false, runtimeState.desiredState.value.shouldRun)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun recovers_storage_component_after_storage_failure() = runTest(timeout = 20.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)

        val server = FlakyServer(failuresBeforeSuccess = 0)
        val flakyStorage = FlakyLoopRecordingManager(failuresBeforeSuccess = 2)
        val watchdog = FakeWatchdogManager()
        val runtimeState = FakeRuntimeStateRepository()
        val runtimeCrash = FakeRuntimeCrashRepository()
        val environmentMonitor = FakeRuntimeEnvironmentMonitor()
        val runtimeSettings = FakeRuntimeSettingsRepository(
            RuntimeSettingsDefaults.value.copy(
                featureFlags = FeatureFlags(
                    mjpegStreaming = false,
                    loopRecording = true,
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
            localHttpServer = server,
            pushToTalkManager = NoopAudioManager(),
            loopRecordingManager = flakyStorage,
            watchdogManager = watchdog,
            runtimeEnvironmentMonitor = environmentMonitor,
            runtimeSettingsRepository = runtimeSettings,
            runtimeCrashRepository = runtimeCrash,
            runtimeStateRepository = runtimeState,
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = scheduler,
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        try {
            advanceTimeBy(250)

            assertTrue(flakyStorage.startCalls >= 3)
            assertTrue(scheduler.recordedDelays.any { it >= 5 })
            assertEquals(
                ComponentHealthStatus.HEALTHY,
                coordinator.health.value.components.getValue(RuntimeComponent.STORAGE).status
            )
        } finally {
            coordinator.stop(persistDesiredState = true)
            advanceUntilIdle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun reconnect_event_triggers_server_recovery() = runTest(timeout = 20.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)

        val server = FlakyServer(failuresBeforeSuccess = 0)
        val watchdog = FakeWatchdogManager()
        val runtimeState = FakeRuntimeStateRepository()
        val runtimeCrash = FakeRuntimeCrashRepository()
        val environmentMonitor = FakeRuntimeEnvironmentMonitor(
            initialConnectivity = NetworkConnectivity(connected = true, transport = "wifi")
        )
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
        val coordinator = ZCamRuntimeCoordinator(
            cameraRuntime = NoopCameraRuntime(),
            localHttpServer = server,
            pushToTalkManager = NoopAudioManager(),
            loopRecordingManager = NoopLoopRecordingManager(),
            watchdogManager = watchdog,
            runtimeEnvironmentMonitor = environmentMonitor,
            runtimeSettingsRepository = runtimeSettings,
            runtimeCrashRepository = runtimeCrash,
            runtimeStateRepository = runtimeState,
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = RecordingBackoffScheduler(),
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        try {
            runCurrent()
            environmentMonitor.setConnectivity(NetworkConnectivity(connected = false, transport = "none"))
            runCurrent()
            environmentMonitor.setConnectivity(NetworkConnectivity(connected = true, transport = "wifi"))
            advanceTimeBy(120)
            runCurrent()

            assertTrue(server.startCalls >= 2)
            assertEquals(
                ComponentHealthStatus.HEALTHY,
                coordinator.health.value.components.getValue(RuntimeComponent.NETWORK).status
            )
        } finally {
            coordinator.stop(persistDesiredState = true)
            advanceUntilIdle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun thermal_transitions_throttle_stream_and_suspend_recording() = runTest(timeout = 20.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)

        val camera = TrackingCameraRuntime()
        val recording = TrackingLoopRecordingManager()
        val watchdog = FakeWatchdogManager()
        val runtimeState = FakeRuntimeStateRepository()
        val runtimeCrash = FakeRuntimeCrashRepository()
        val environmentMonitor = FakeRuntimeEnvironmentMonitor(
            initialThermal = ThermalBand.NOMINAL
        )
        val runtimeSettings = FakeRuntimeSettingsRepository(
            RuntimeSettingsDefaults.value.copy(
                stream = RuntimeSettingsDefaults.value.stream.copy(fps = 15),
                featureFlags = FeatureFlags(
                    mjpegStreaming = true,
                    loopRecording = true,
                    audioPushToTalk = false,
                    audioLive = false,
                    audioPlayback = false,
                    trustedDevices = true,
                    watchdogRecovery = true
                )
            )
        )
        val coordinator = ZCamRuntimeCoordinator(
            cameraRuntime = camera,
            localHttpServer = FlakyServer(failuresBeforeSuccess = 0),
            pushToTalkManager = NoopAudioManager(),
            loopRecordingManager = recording,
            watchdogManager = watchdog,
            runtimeEnvironmentMonitor = environmentMonitor,
            runtimeSettingsRepository = runtimeSettings,
            runtimeCrashRepository = runtimeCrash,
            runtimeStateRepository = runtimeState,
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = RecordingBackoffScheduler(),
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        try {
            runCurrent()
            environmentMonitor.setThermal(ThermalBand.THROTTLED)
            advanceTimeBy(100)
            environmentMonitor.setThermal(ThermalBand.CRITICAL)
            advanceTimeBy(100)
            environmentMonitor.setThermal(ThermalBand.NOMINAL)
            advanceTimeBy(120)

            assertTrue(camera.startConfigs.size >= 3)
            val throttled = camera.startConfigs.firstOrNull { it.fps <= 10 }
            assertTrue(throttled != null)
            assertTrue(recording.stopCalls >= 1)
            assertTrue(recording.startCalls >= 2)
            assertEquals(
                ComponentHealthStatus.HEALTHY,
                coordinator.health.value.components.getValue(RuntimeComponent.THERMAL).status
            )
        } finally {
            coordinator.stop(persistDesiredState = true)
            advanceUntilIdle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun rear_lens_change_rebinds_camera_and_preserves_recording_runtime() = runTest(timeout = 20.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)

        val camera = TrackingCameraRuntime()
        val recording = TrackingLoopRecordingManager()
        val runtimeSettings = FakeRuntimeSettingsRepository(
            RuntimeSettingsDefaults.value.copy(
                featureFlags = FeatureFlags(
                    mjpegStreaming = true,
                    loopRecording = true,
                    audioPushToTalk = false,
                    audioLive = false,
                    audioPlayback = false,
                    trustedDevices = true,
                    watchdogRecovery = true
                )
            )
        )
        val coordinator = ZCamRuntimeCoordinator(
            cameraRuntime = camera,
            localHttpServer = FlakyServer(failuresBeforeSuccess = 0),
            pushToTalkManager = NoopAudioManager(),
            loopRecordingManager = recording,
            watchdogManager = FakeWatchdogManager(),
            runtimeEnvironmentMonitor = FakeRuntimeEnvironmentMonitor(),
            runtimeSettingsRepository = runtimeSettings,
            runtimeCrashRepository = FakeRuntimeCrashRepository(),
            runtimeStateRepository = FakeRuntimeStateRepository(),
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = RecordingBackoffScheduler(),
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        try {
            runCurrent()

            runtimeSettings.updateSettings(
                runtimeSettings.settings.value.copy(
                    stream = runtimeSettings.settings.value.stream.copy(
                        rearLens = RearCameraLens.ULTRA_WIDE
                    )
                )
            )
            advanceTimeBy(150)
            runCurrent()

            assertEquals(2, camera.startConfigs.size)
            assertEquals(RearCameraLens.MAIN, camera.startConfigs.first().rearLens)
            assertEquals(RearCameraLens.ULTRA_WIDE, camera.startConfigs.last().rearLens)
            assertTrue(camera.stopCalls >= 1)
            assertTrue(recording.stopCalls >= 1)
            assertTrue(recording.startCalls >= 2)
            assertEquals(
                ComponentHealthStatus.HEALTHY,
                coordinator.health.value.components.getValue(RuntimeComponent.CAMERA).status
            )
            assertEquals(
                ComponentHealthStatus.HEALTHY,
                coordinator.health.value.components.getValue(RuntimeComponent.STORAGE).status
            )
        } finally {
            coordinator.stop(persistDesiredState = true)
            advanceUntilIdle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun remains_stable_over_24h_virtual_runtime() = runTest(timeout = 30.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)

        val server = FlakyServer(failuresBeforeSuccess = 0)
        val watchdog = FakeWatchdogManager()
        val runtimeState = FakeRuntimeStateRepository()
        val runtimeCrash = FakeRuntimeCrashRepository()
        val environmentMonitor = FakeRuntimeEnvironmentMonitor()
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
            localHttpServer = server,
            pushToTalkManager = NoopAudioManager(),
            loopRecordingManager = NoopLoopRecordingManager(),
            watchdogManager = watchdog,
            runtimeEnvironmentMonitor = environmentMonitor,
            runtimeSettingsRepository = runtimeSettings,
            runtimeCrashRepository = runtimeCrash,
            runtimeStateRepository = runtimeState,
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = scheduler,
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        try {
            advanceTimeBy(24.hours.inWholeMilliseconds)
            assertEquals(1, server.startCalls)
            assertEquals(
                ComponentHealthStatus.HEALTHY,
                coordinator.health.value.components.getValue(RuntimeComponent.SERVER).status
            )
        } finally {
            coordinator.stop(persistDesiredState = true)
            advanceUntilIdle()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun marks_runtime_dirty_on_start_and_clean_on_stop() = runTest(timeout = 20.seconds) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = TestDispatcherProvider(dispatcher)
        val runtimeCrash = FakeRuntimeCrashRepository()
        val coordinator = ZCamRuntimeCoordinator(
            cameraRuntime = NoopCameraRuntime(),
            localHttpServer = FlakyServer(failuresBeforeSuccess = 0),
            pushToTalkManager = NoopAudioManager(),
            loopRecordingManager = NoopLoopRecordingManager(),
            watchdogManager = FakeWatchdogManager(),
            runtimeEnvironmentMonitor = FakeRuntimeEnvironmentMonitor(),
            runtimeSettingsRepository = FakeRuntimeSettingsRepository(
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
            ),
            runtimeCrashRepository = runtimeCrash,
            runtimeStateRepository = FakeRuntimeStateRepository(),
            runtimeHealthRepository = InMemoryRuntimeHealthRepository(),
            recoveryPolicy = RecoveryPolicy(baseDelayMs = 5, maxDelayMs = 40, maxAttemptsBeforeCooldown = 4, cooldownMs = 100),
            retryBackoffScheduler = RecordingBackoffScheduler(),
            dispatchers = dispatchers,
            logger = NoopLogger()
        )

        coordinator.start()
        runCurrent()
        assertTrue(runtimeCrash.state.value.runtimeDirty)

        coordinator.stop(persistDesiredState = true)
        advanceUntilIdle()
        assertFalse(runtimeCrash.state.value.runtimeDirty)
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
        private val _events = MutableSharedFlow<RecoveryRequest>(
            replay = 1,
            extraBufferCapacity = 16
        )

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
        val recordedDelays = ArrayDeque<Long>()

        override suspend fun pause(millis: Long) {
            if (recordedDelays.size >= 512) {
                recordedDelays.removeFirst()
            }
            recordedDelays.addLast(millis)
            delay(millis.coerceAtLeast(1L))
        }
    }

    private class FlakyLoopRecordingManager(
        private var failuresBeforeSuccess: Int
    ) : LoopRecordingManager {
        var startCalls: Int = 0
            private set
        private var healthy = false

        override suspend fun start(config: com.zcam.core.domain.config.LoopRecordingConfig) {
            startCalls += 1
            if (failuresBeforeSuccess > 0) {
                failuresBeforeSuccess -= 1
                healthy = false
                throw IllegalStateException("simulated storage start failure")
            }
            healthy = true
        }

        override suspend fun stop() {
            healthy = false
        }

        override suspend fun isHealthy(): Boolean = healthy

        override suspend fun forceRetentionSweep() = Unit

        override suspend fun queryRecordings(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<RecordingClipSummary> = emptyList()

        override suspend fun queryRecordingEvents(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<RecordingEventSummary> = emptyList()

        override suspend fun resolveRecordingFile(fileName: String): java.io.File? = null
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

    private class FakeRuntimeCrashRepository : RuntimeCrashRepository {
        private val _state = MutableStateFlow(RuntimeCrashState())
        override val state: StateFlow<RuntimeCrashState> = _state.asStateFlow()

        override suspend fun markRuntimeDirty() {
            _state.value = _state.value.copy(
                runtimeDirty = true,
                lastRuntimeMarkerEpochMs = System.currentTimeMillis()
            )
        }

        override suspend fun markRuntimeClean() {
            _state.value = _state.value.copy(
                runtimeDirty = false,
                lastRuntimeMarkerEpochMs = System.currentTimeMillis()
            )
        }

        override suspend fun markCrash(reason: String) {
            _state.value = _state.value.copy(
                runtimeDirty = true,
                lastRuntimeMarkerEpochMs = System.currentTimeMillis(),
                lastCrashEpochMs = System.currentTimeMillis(),
                lastCrashReason = reason
            )
        }

        override suspend fun markRecovered() {
            _state.value = _state.value.copy(
                lastRecoveryEpochMs = System.currentTimeMillis()
            )
        }
    }

    private class FakeRuntimeEnvironmentMonitor(
        initialThermal: ThermalBand = ThermalBand.NOMINAL,
        initialConnectivity: NetworkConnectivity = NetworkConnectivity(connected = true, transport = "wifi")
    ) : RuntimeEnvironmentMonitor {
        private val _thermal = MutableStateFlow(initialThermal)
        private val _network = MutableStateFlow(initialConnectivity)

        override val thermalBand: StateFlow<ThermalBand> = _thermal.asStateFlow()
        override val networkConnectivity: StateFlow<NetworkConnectivity> = _network.asStateFlow()

        override suspend fun start() = Unit
        override suspend fun stop() = Unit

        suspend fun setThermal(value: ThermalBand) {
            _thermal.emit(value)
        }

        suspend fun setConnectivity(value: NetworkConnectivity) {
            _network.emit(value)
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

        override suspend fun setTorch(enabled: Boolean): com.zcam.camera.CameraControlCommandResult {
            return com.zcam.camera.CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = "ok"
            )
        }

        override suspend fun setNightMode(enabled: Boolean): com.zcam.camera.CameraControlCommandResult {
            return com.zcam.camera.CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = "ok"
            )
        }

        override suspend fun setZoomLinear(linearZoom: Float): com.zcam.camera.CameraControlCommandResult {
            return com.zcam.camera.CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = "ok"
            )
        }

        override fun controlsSnapshot(): com.zcam.camera.CameraControlsSnapshot {
            return com.zcam.camera.CameraControlsSnapshot(
                running = true,
                torchEnabled = false,
                nightModeEnabled = false,
                lowLightBoostSupported = true,
                lastError = null
            )
        }
    }

    private class TrackingCameraRuntime : CameraRuntime {
        val startConfigs = mutableListOf<com.zcam.core.domain.config.StreamConfig>()
        var stopCalls: Int = 0
            private set
        private var healthy = false

        override suspend fun start(config: com.zcam.core.domain.config.StreamConfig) {
            startConfigs += config
            healthy = true
        }

        override suspend fun stop() {
            stopCalls += 1
            healthy = false
        }

        override suspend fun isHealthy(): Boolean = healthy

        override fun latestFrame(): ByteArray = byteArrayOf(1)

        override suspend fun setTorch(enabled: Boolean): com.zcam.camera.CameraControlCommandResult {
            return com.zcam.camera.CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = "ok"
            )
        }

        override suspend fun setNightMode(enabled: Boolean): com.zcam.camera.CameraControlCommandResult {
            return com.zcam.camera.CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = "ok"
            )
        }

        override suspend fun setZoomLinear(linearZoom: Float): com.zcam.camera.CameraControlCommandResult {
            return com.zcam.camera.CameraControlCommandResult.Success(
                snapshot = controlsSnapshot(),
                message = "ok"
            )
        }

        override fun controlsSnapshot(): com.zcam.camera.CameraControlsSnapshot {
            return com.zcam.camera.CameraControlsSnapshot(
                running = healthy,
                torchEnabled = false,
                nightModeEnabled = false,
                lowLightBoostSupported = true
            )
        }
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
        override suspend fun handleLiveMode(
            mode: com.zcam.audio.AudioLiveMode,
            enabled: Boolean
        ): com.zcam.audio.AudioCommandResult {
            return com.zcam.audio.AudioCommandResult.Success(snapshotState(), "ok")
        }

        override suspend fun playStoredAudio(
            request: com.zcam.audio.AudioPlaybackRequest
        ): com.zcam.audio.AudioCommandResult {
            return com.zcam.audio.AudioCommandResult.Success(snapshotState(), "ok")
        }

        override suspend fun setVolume(levelPercent: Int): com.zcam.audio.AudioCommandResult {
            return com.zcam.audio.AudioCommandResult.Success(snapshotState(), "ok")
        }

        override fun transportConfig(): AudioTransportConfig = AudioTransportConfig()

        override suspend fun registerLiveAudioSubscriber(
            subscriberId: String,
            onFrame: (ByteArray) -> Unit
        ): Boolean = true

        override suspend fun unregisterLiveAudioSubscriber(subscriberId: String) = Unit

        override suspend fun openPushToTalkStream(streamId: String): Boolean = true

        override suspend fun submitPushToTalkAudio(streamId: String, pcmFrame: ByteArray): Boolean = true

        override suspend fun closePushToTalkStream(streamId: String) = Unit

        override fun snapshotState(): com.zcam.audio.AudioStateSnapshot {
            return com.zcam.audio.AudioStateSnapshot(
                engineStarted = true,
                transmitting = false,
                liveListening = false,
                playingBack = false,
                activeClipId = null,
                volumePercent = 40,
                minVolumePercent = 0,
                maxVolumePercent = 85,
                aversiveCooldownMs = 10_000L,
                aversiveCooldownRemainingMs = 0L
            )
        }
    }

    private class NoopLoopRecordingManager : LoopRecordingManager {
        override suspend fun start(config: com.zcam.core.domain.config.LoopRecordingConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun isHealthy() = true
        override suspend fun forceRetentionSweep() = Unit
        override suspend fun queryRecordings(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<RecordingClipSummary> = emptyList()

        override suspend fun queryRecordingEvents(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<RecordingEventSummary> = emptyList()

        override suspend fun resolveRecordingFile(fileName: String): java.io.File? = null
    }

    private class TrackingLoopRecordingManager : LoopRecordingManager {
        var startCalls: Int = 0
            private set
        var stopCalls: Int = 0
            private set
        private var healthy = false

        override suspend fun start(config: com.zcam.core.domain.config.LoopRecordingConfig) {
            startCalls += 1
            healthy = true
        }

        override suspend fun stop() {
            stopCalls += 1
            healthy = false
        }

        override suspend fun isHealthy(): Boolean = healthy
        override suspend fun forceRetentionSweep() = Unit
        override suspend fun queryRecordings(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<RecordingClipSummary> = emptyList()

        override suspend fun queryRecordingEvents(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<RecordingEventSummary> = emptyList()

        override suspend fun resolveRecordingFile(fileName: String): java.io.File? = null
    }

    private class NoopLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
