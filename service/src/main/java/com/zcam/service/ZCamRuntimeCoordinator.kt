package com.zcam.service

import com.zcam.audio.PushToTalkManager
import com.zcam.camera.CameraRuntime
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.StreamConfig
import com.zcam.core.domain.config.VideoResolution
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeCrashRepository
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.domain.watchdog.RecoveryReason
import com.zcam.core.domain.watchdog.RecoveryRequest
import com.zcam.core.domain.watchdog.WatchdogComponentHealth
import com.zcam.core.domain.watchdog.WatchdogComponentStatus
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import com.zcam.server.LocalHttpServer
import com.zcam.service.runtime.ComponentHealthStatus
import com.zcam.service.runtime.NetworkConnectivity
import com.zcam.service.runtime.RecoveryPolicy
import com.zcam.service.runtime.RetryBackoffScheduler
import com.zcam.service.runtime.RuntimeEnvironmentMonitor
import com.zcam.service.runtime.RuntimeComponent
import com.zcam.service.runtime.RuntimeHealthRepository
import com.zcam.service.runtime.ThermalBand
import com.zcam.storage.LoopRecordingManager
import com.zcam.watchdog.WatchdogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZCamRuntimeCoordinator @Inject constructor(
    private val cameraRuntime: CameraRuntime,
    private val localHttpServer: LocalHttpServer,
    private val pushToTalkManager: PushToTalkManager,
    private val loopRecordingManager: LoopRecordingManager,
    private val watchdogManager: WatchdogManager,
    private val runtimeEnvironmentMonitor: RuntimeEnvironmentMonitor,
    private val runtimeSettingsRepository: RuntimeSettingsRepository,
    private val runtimeCrashRepository: RuntimeCrashRepository,
    private val runtimeStateRepository: RuntimeStateRepository,
    private val runtimeHealthRepository: RuntimeHealthRepository,
    private val recoveryPolicy: RecoveryPolicy,
    private val retryBackoffScheduler: RetryBackoffScheduler,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) {

    val health: StateFlow<com.zcam.service.runtime.RuntimeHealthSnapshot>
        get() = runtimeHealthRepository.health

    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private val isRunning = AtomicBoolean(false)
    private val componentLocks = RuntimeComponent.entries.associateWith { Mutex() }
    private val recoveryAttempts = ConcurrentHashMap<RuntimeComponent, Int>()

    private val componentByKey = ConcurrentHashMap<String, RuntimeComponent>()

    private var heartbeatJob: Job? = null
    private var recoveryCollectorJob: Job? = null
    private var settingsCollectorJob: Job? = null
    private var thermalCollectorJob: Job? = null
    private var networkCollectorJob: Job? = null

    @Volatile
    private var activeComponents: Map<RuntimeComponent, ManagedComponent> = emptyMap()
    @Volatile
    private var watchdogRecoveryEnabled: Boolean = true
    @Volatile
    private var activeRuntimeSettings: RuntimeSettings = RuntimeSettingsDefaults.value
    @Volatile
    private var activeStreamConfig: StreamConfig = RuntimeSettingsDefaults.value.stream
    @Volatile
    private var activeThermalBand: ThermalBand = ThermalBand.NOMINAL
    @Volatile
    private var lastNetworkConnectivity: NetworkConnectivity = NetworkConnectivity(connected = true)
    @Volatile
    private var storageSuspendedByThermal: Boolean = false

    suspend fun start() = withContext(dispatchers.io) {
        if (!isRunning.compareAndSet(false, true)) {
            logger.i(LogEventId.RUNTIME_ALREADY_RUNNING, "Runtime start ignored, already running")
            return@withContext
        }

        runCatching {
            runtimeStateRepository.setDesiredRunning(true)
            runtimeCrashRepository.markRuntimeDirty()
            runtimeHealthRepository.reset()
            recoveryAttempts.clear()
            storageSuspendedByThermal = false

            val settings = runtimeSettingsRepository.settings.first()
            activeRuntimeSettings = settings
            activeStreamConfig = settings.stream
            watchdogRecoveryEnabled = settings.featureFlags.watchdogRecovery
            activeComponents = buildActiveComponents(settings)
            componentByKey.clear()
            activeComponents.values.forEach { componentByKey[it.watchdogKey] = it.component }

            logger.i(LogEventId.RUNTIME_START_SEQUENCE, "Runtime start sequence")

            runtimeEnvironmentMonitor.start()
            lastNetworkConnectivity = runtimeEnvironmentMonitor.networkConnectivity.value
            activeThermalBand = runtimeEnvironmentMonitor.thermalBand.value

            watchdogManager.start()
            registerWatchdogComponents()
            startSettingsCollector()
            startRecoveryCollector()
            startEnvironmentCollectors()

            for (component in startOrder()) {
                startComponent(component)
            }

            applyThermalPolicy(activeThermalBand)

            startHeartbeatLoop()
        }.onFailure { error ->
            runCatching {
                runtimeCrashRepository.markCrash("runtime startup failed: ${error.message}")
            }
            logger.e(LogEventId.COMPONENT_FAILED, error, "Runtime startup failed")
            isRunning.set(false)
            stopInternal(persistDesiredState = false)
        }
    }

    suspend fun stop(persistDesiredState: Boolean) = withContext(dispatchers.io) {
        if (!isRunning.compareAndSet(true, false)) {
            logger.i(LogEventId.RUNTIME_ALREADY_STOPPED, "Runtime stop ignored, already stopped")
            if (persistDesiredState) {
                runtimeStateRepository.setDesiredRunning(false)
            }
            return@withContext
        }

        stopInternal(persistDesiredState)
    }

    private suspend fun stopInternal(persistDesiredState: Boolean) {
        logger.i(LogEventId.RUNTIME_STOP_SEQUENCE, "Runtime stop sequence")

        if (persistDesiredState) {
            runtimeStateRepository.setDesiredRunning(false)
        }

        heartbeatJob?.cancel()
        heartbeatJob = null

        recoveryCollectorJob?.cancel()
        recoveryCollectorJob = null

        settingsCollectorJob?.cancel()
        settingsCollectorJob = null

        thermalCollectorJob?.cancel()
        thermalCollectorJob = null

        networkCollectorJob?.cancel()
        networkCollectorJob = null

        for (component in stopOrder()) {
            stopComponent(component)
        }

        runCatching {
            runtimeEnvironmentMonitor.stop()
        }.onFailure { error ->
            logger.w(LogEventId.COMPONENT_FAILED, "Failed to stop runtime environment monitor: ${error.message}")
        }

        watchdogManager.stop()
        runtimeHealthRepository.mark(
            component = RuntimeComponent.WATCHDOG,
            status = ComponentHealthStatus.STOPPED,
            eventId = LogEventId.WATCHDOG_STOPPED,
            message = "watchdog stopped"
        )
        runtimeHealthRepository.mark(
            component = RuntimeComponent.THERMAL,
            status = ComponentHealthStatus.STOPPED,
            eventId = LogEventId.RUNTIME_STOP_SEQUENCE,
            message = "thermal monitor stopped"
        )
        runtimeHealthRepository.mark(
            component = RuntimeComponent.NETWORK,
            status = ComponentHealthStatus.STOPPED,
            eventId = LogEventId.RUNTIME_STOP_SEQUENCE,
            message = "network monitor stopped"
        )

        activeComponents = emptyMap()
        componentByKey.clear()
        recoveryAttempts.clear()
        watchdogRecoveryEnabled = true
        storageSuspendedByThermal = false
        activeRuntimeSettings = RuntimeSettingsDefaults.value
        activeStreamConfig = RuntimeSettingsDefaults.value.stream
        activeThermalBand = ThermalBand.NOMINAL
        lastNetworkConnectivity = NetworkConnectivity(connected = true)

        runCatching {
            runtimeCrashRepository.markRuntimeClean()
        }
    }

    private fun startRecoveryCollector() {
        if (recoveryCollectorJob?.isActive == true) return

        recoveryCollectorJob = runtimeScope.launch {
            watchdogManager.recoveryEvents.collect { request ->
                if (!watchdogRecoveryEnabled) return@collect
                val component = componentByKey[request.component] ?: return@collect
                if (component == RuntimeComponent.STORAGE && storageSuspendedByThermal) {
                    return@collect
                }
                launch {
                    recoverComponent(component, request)
                }
            }
        }
    }

    private fun startSettingsCollector() {
        if (settingsCollectorJob?.isActive == true) return

        settingsCollectorJob = runtimeScope.launch {
            var firstEmission = true
            runtimeSettingsRepository.settings.collect { settings ->
                val previousSettings = activeRuntimeSettings
                activeRuntimeSettings = settings
                watchdogRecoveryEnabled = settings.featureFlags.watchdogRecovery
                val desiredStream = thermalAdjustedStream(settings.stream, activeThermalBand)

                if (firstEmission) {
                    activeStreamConfig = desiredStream
                    firstEmission = false
                    return@collect
                }

                if (!isRunning.get()) {
                    activeStreamConfig = desiredStream
                    return@collect
                }

                activeStreamConfig = desiredStream
                val lensChanged = settings.stream.rearLens != previousSettings.stream.rearLens
                if (lensChanged) {
                    handleRearLensSelectionChanged(desiredStream)
                }
            }
        }
    }

    private fun startEnvironmentCollectors() {
        if (thermalCollectorJob?.isActive != true) {
            thermalCollectorJob = runtimeScope.launch {
                runtimeEnvironmentMonitor.thermalBand.collect { band ->
                    if (band == activeThermalBand) return@collect
                    handleThermalBandChanged(band)
                }
            }
        }

        if (networkCollectorJob?.isActive != true) {
            networkCollectorJob = runtimeScope.launch {
                runtimeEnvironmentMonitor.networkConnectivity.collect { connectivity ->
                    handleNetworkConnectivityChanged(connectivity)
                }
            }
        }
    }

    private suspend fun handleThermalBandChanged(band: ThermalBand) {
        activeThermalBand = band
        val status = when (band) {
            ThermalBand.NOMINAL -> ComponentHealthStatus.HEALTHY
            ThermalBand.THROTTLED -> ComponentHealthStatus.RECOVERING
            ThermalBand.CRITICAL -> ComponentHealthStatus.FAILED
        }
        runtimeHealthRepository.mark(
            component = RuntimeComponent.THERMAL,
            status = status,
            eventId = LogEventId.THERMAL_BAND_CHANGED,
            message = "thermal band=${band.name.lowercase()}"
        )
        logger.w(LogEventId.THERMAL_BAND_CHANGED, "Thermal band changed to ${band.name}")
        applyThermalPolicy(band)
    }

    private suspend fun handleNetworkConnectivityChanged(connectivity: NetworkConnectivity) {
        val previous = lastNetworkConnectivity
        lastNetworkConnectivity = connectivity
        if (previous == connectivity) return

        if (connectivity.connected) {
            runtimeHealthRepository.mark(
                component = RuntimeComponent.NETWORK,
                status = ComponentHealthStatus.HEALTHY,
                eventId = LogEventId.NETWORK_RECONNECTED,
                message = "network connected via ${connectivity.transport}"
            )
            logger.i(LogEventId.NETWORK_RECONNECTED, "Network connected via ${connectivity.transport}")
            if (!previous.connected && isRunning.get()) {
                logger.w(LogEventId.RECOVERY_RECONNECT_TRIGGERED, "Triggering server reconnect recovery")
                runtimeScope.launch {
                    recoverComponent(
                        component = RuntimeComponent.SERVER,
                        request = RecoveryRequest(
                            component = SERVER_KEY,
                            reason = RecoveryReason.RESOURCE_LOST,
                            details = "network reconnected on ${connectivity.transport}",
                            attempt = 0
                        )
                    )
                }
            }
        } else {
            runtimeHealthRepository.mark(
                component = RuntimeComponent.NETWORK,
                status = ComponentHealthStatus.RECOVERING,
                eventId = LogEventId.NETWORK_LOST,
                message = "network unavailable"
            )
            logger.w(LogEventId.NETWORK_LOST, "Network connectivity lost")
        }
    }

    private suspend fun handleRearLensSelectionChanged(desiredStream: StreamConfig) {
        if (!activeComponents.containsKey(RuntimeComponent.CAMERA)) return

        val storageLock = componentLocks[RuntimeComponent.STORAGE]
        val cameraLock = componentLocks.getValue(RuntimeComponent.CAMERA)

        suspend fun rebindCameraPipeline() {
            logger.i(
                LogEventId.RUNTIME_START_SEQUENCE,
                "Rebinding camera pipeline for rear lens ${desiredStream.rearLens.wireName}"
            )
            val storageWasRunning = activeComponents.containsKey(RuntimeComponent.STORAGE) && isStorageRuntimeActive()
            if (storageWasRunning) {
                stopComponent(RuntimeComponent.STORAGE)
            }

            stopComponent(RuntimeComponent.CAMERA)
            activeStreamConfig = desiredStream
            startComponent(RuntimeComponent.CAMERA)

            val cameraHealthy = health.value.components[RuntimeComponent.CAMERA]?.status == ComponentHealthStatus.HEALTHY
            if (storageWasRunning && !storageSuspendedByThermal && cameraHealthy) {
                startComponent(RuntimeComponent.STORAGE)
            }
        }

        if (storageLock == null) {
            cameraLock.withLock {
                if (isRunning.get()) {
                    rebindCameraPipeline()
                }
            }
        } else {
            storageLock.withLock {
                cameraLock.withLock {
                    if (isRunning.get()) {
                        rebindCameraPipeline()
                    }
                }
            }
        }
    }

    private suspend fun applyThermalPolicy(band: ThermalBand) {
        val desiredStream = thermalAdjustedStream(activeRuntimeSettings.stream, band)
        val shouldSuspendStorage = band == ThermalBand.CRITICAL
        val hasCamera = activeComponents.containsKey(RuntimeComponent.CAMERA)

        val streamChanged = desiredStream != activeStreamConfig
        if (hasCamera && streamChanged) {
            val storageWasRunning = isStorageRuntimeActive()
            if (storageWasRunning) {
                stopComponent(RuntimeComponent.STORAGE)
            }
            stopComponent(RuntimeComponent.CAMERA)
            activeStreamConfig = desiredStream
            startComponent(RuntimeComponent.CAMERA)
            logger.w(
                LogEventId.THERMAL_THROTTLE_APPLIED,
                "Applied thermal stream profile ${desiredStream.resolution.width}x${desiredStream.resolution.height}@${desiredStream.fps}"
            )
            if (storageWasRunning && !shouldSuspendStorage) {
                startComponent(RuntimeComponent.STORAGE)
            }
        } else {
            activeStreamConfig = desiredStream
        }

        if (!activeComponents.containsKey(RuntimeComponent.STORAGE)) return

        if (shouldSuspendStorage) {
            if (isStorageRuntimeActive()) {
                stopComponent(RuntimeComponent.STORAGE)
                logger.w(LogEventId.THERMAL_RECORDING_SUSPENDED, "Recording suspended due to critical thermal state")
            }
            storageSuspendedByThermal = true
        } else if (storageSuspendedByThermal) {
            startComponent(RuntimeComponent.STORAGE)
            storageSuspendedByThermal = false
            logger.i(LogEventId.THERMAL_RECORDING_RESUMED, "Recording resumed after thermal recovery")
        }
    }

    private fun isStorageRuntimeActive(): Boolean {
        val status = health.value.components[RuntimeComponent.STORAGE]?.status ?: return false
        return status == ComponentHealthStatus.HEALTHY ||
            status == ComponentHealthStatus.STARTING ||
            status == ComponentHealthStatus.RECOVERING
    }

    private fun thermalAdjustedStream(base: StreamConfig, band: ThermalBand): StreamConfig {
        return when (band) {
            ThermalBand.NOMINAL -> base
            ThermalBand.THROTTLED -> {
                val fps = base.fps.coerceAtMost(10)
                val width = even((base.resolution.width * 3 / 4).coerceAtLeast(640))
                val height = even((base.resolution.height * 3 / 4).coerceAtLeast(360))
                base.copy(
                    fps = fps,
                    resolution = VideoResolution(width = width, height = height)
                )
            }
            ThermalBand.CRITICAL -> {
                val fps = base.fps.coerceAtMost(6)
                val width = even(base.resolution.width.coerceAtMost(640))
                val height = even(base.resolution.height.coerceAtMost(360))
                base.copy(
                    fps = fps,
                    resolution = VideoResolution(width = width, height = height)
                )
            }
        }
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else (value - 1).coerceAtLeast(2)

    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return

        heartbeatJob = runtimeScope.launch {
            while (isActive && isRunning.get()) {
                activeComponents.values.forEach { managed ->
                    val status = health.value.components[managed.component]?.status
                    val shouldProbe = status == ComponentHealthStatus.HEALTHY || status == ComponentHealthStatus.STARTING
                    if (!shouldProbe) return@forEach

                    val isHealthy = runCatching { managed.healthCheck.invoke() }.getOrDefault(false)
                    if (isHealthy) {
                        watchdogManager.heartbeat(managed.watchdogKey)
                    } else {
                        val attempt = (recoveryAttempts[managed.component] ?: 0) + 1
                        recoveryAttempts[managed.component] = attempt
                        logger.w(
                            LogEventId.COMPONENT_FAILED,
                            "Health probe failed for ${managed.component.name}, attempt=$attempt"
                        )
                        runtimeHealthRepository.mark(
                            component = managed.component,
                            status = ComponentHealthStatus.FAILED,
                            eventId = LogEventId.COMPONENT_FAILED,
                            message = "health probe failed",
                            recoveryAttempts = attempt
                        )
                        watchdogManager.updateComponentStatus(
                            component = managed.watchdogKey,
                            status = WatchdogComponentStatus.FAILED,
                            details = "health probe failed"
                        )
                        watchdogManager.requestRecovery(
                            RecoveryRequest(
                                component = managed.watchdogKey,
                                reason = RecoveryReason.RESOURCE_LOST,
                                details = "health probe failed for ${managed.component.name.lowercase()}",
                                attempt = attempt
                            )
                        )
                    }
                }
                watchdogManager.heartbeat(WATCHDOG_KEY)
                retryBackoffScheduler.pause(HEARTBEAT_INTERVAL_MS)
            }
        }
    }

    private suspend fun registerWatchdogComponents() {
        watchdogManager.registerComponent(WATCHDOG_KEY, timeoutMs = WATCHDOG_TIMEOUT_MS)
        watchdogManager.updateComponentStatus(WATCHDOG_KEY, WatchdogComponentStatus.HEALTHY, "watchdog runtime active")
        runtimeHealthRepository.mark(
            component = RuntimeComponent.WATCHDOG,
            status = ComponentHealthStatus.HEALTHY,
            eventId = LogEventId.WATCHDOG_STARTED,
            message = "watchdog active"
        )

        activeComponents.values.forEach { managed ->
            watchdogManager.registerComponent(managed.watchdogKey, timeoutMs = managed.timeoutMs)
            watchdogManager.updateComponentStatus(
                component = managed.watchdogKey,
                status = WatchdogComponentStatus.STARTING,
                details = "component registered"
            )
        }

        runtimeHealthRepository.mark(
            component = RuntimeComponent.THERMAL,
            status = ComponentHealthStatus.HEALTHY,
            eventId = LogEventId.THERMAL_BAND_CHANGED,
            message = "thermal band=${activeThermalBand.name.lowercase()}"
        )
        val networkStatus = if (lastNetworkConnectivity.connected) {
            ComponentHealthStatus.HEALTHY
        } else {
            ComponentHealthStatus.RECOVERING
        }
        runtimeHealthRepository.mark(
            component = RuntimeComponent.NETWORK,
            status = networkStatus,
            eventId = if (lastNetworkConnectivity.connected) LogEventId.NETWORK_RECONNECTED else LogEventId.NETWORK_LOST,
            message = if (lastNetworkConnectivity.connected) {
                "network connected via ${lastNetworkConnectivity.transport}"
            } else {
                "network unavailable"
            }
        )
    }

    private suspend fun startComponent(component: RuntimeComponent) {
        val managed = activeComponents[component] ?: return

        runtimeHealthRepository.mark(
            component = component,
            status = ComponentHealthStatus.STARTING,
            eventId = LogEventId.COMPONENT_STARTING,
            message = "${component.name.lowercase()} starting"
        )
        watchdogManager.updateComponentStatus(
            component = managed.watchdogKey,
            status = WatchdogComponentStatus.STARTING,
            details = "start requested"
        )
        logger.i(LogEventId.COMPONENT_STARTING, "Starting ${component.name}")

        runCatching {
            managed.start.invoke()
        }.onSuccess {
            recoveryAttempts[component] = 0
            runtimeHealthRepository.mark(
                component = component,
                status = ComponentHealthStatus.HEALTHY,
                eventId = LogEventId.COMPONENT_HEALTHY,
                message = "${component.name.lowercase()} healthy"
            )
            watchdogManager.updateComponentStatus(
                component = managed.watchdogKey,
                status = WatchdogComponentStatus.HEALTHY,
                details = "started"
            )
            watchdogManager.heartbeat(managed.watchdogKey)
            logger.i(LogEventId.COMPONENT_HEALTHY, "${component.name} healthy")
        }.onFailure { error ->
            val attempt = (recoveryAttempts[component] ?: 0) + 1
            recoveryAttempts[component] = attempt
            runtimeHealthRepository.mark(
                component = component,
                status = ComponentHealthStatus.FAILED,
                eventId = LogEventId.COMPONENT_FAILED,
                message = "${component.name.lowercase()} failed: ${error.message}",
                recoveryAttempts = attempt
            )
            watchdogManager.updateComponentStatus(
                component = managed.watchdogKey,
                status = WatchdogComponentStatus.FAILED,
                details = error.message
            )
            logger.e(LogEventId.COMPONENT_FAILED, error, "${component.name} start failed")
            watchdogManager.requestRecovery(
                RecoveryRequest(
                    component = managed.watchdogKey,
                    reason = RecoveryReason.START_FAILURE,
                    details = "start failure: ${error.message}",
                    attempt = attempt
                )
            )
        }
    }

    private suspend fun stopComponent(component: RuntimeComponent) {
        val managed = activeComponents[component] ?: return
        runtimeHealthRepository.mark(
            component = component,
            status = ComponentHealthStatus.STOPPING,
            eventId = LogEventId.COMPONENT_STOPPING,
            message = "${component.name.lowercase()} stopping"
        )
        logger.i(LogEventId.COMPONENT_STOPPING, "Stopping ${component.name}")

        runCatching {
            managed.stop.invoke()
        }.onFailure { error ->
            logger.e(LogEventId.COMPONENT_FAILED, error, "${component.name} stop failed")
        }

        runtimeHealthRepository.mark(
            component = component,
            status = ComponentHealthStatus.STOPPED,
            eventId = LogEventId.COMPONENT_STOPPED,
            message = "${component.name.lowercase()} stopped"
        )
        watchdogManager.updateComponentStatus(
            component = managed.watchdogKey,
            status = WatchdogComponentStatus.STOPPED,
            details = "stopped"
        )
    }

    private suspend fun recoverComponent(component: RuntimeComponent, request: RecoveryRequest) {
        val managed = activeComponents[component] ?: return
        val mutex = componentLocks.getValue(component)

        mutex.withLock {
            if (!isRunning.get()) return

            var attempt = (recoveryAttempts[component] ?: 0)

            while (isRunning.get()) {
                attempt += 1
                recoveryAttempts[component] = attempt

                val useCooldown = attempt > recoveryPolicy.maxAttemptsBeforeCooldown
                val delayMs = if (useCooldown) recoveryPolicy.cooldownMs else recoveryPolicy.nextDelayMs(attempt)

                runtimeHealthRepository.mark(
                    component = component,
                    status = ComponentHealthStatus.RECOVERING,
                    eventId = LogEventId.RECOVERY_SCHEDULED,
                    message = "recovery in ${delayMs} ms (${request.reason})",
                    recoveryAttempts = attempt
                )

                if (useCooldown) {
                    logger.w(
                        LogEventId.RECOVERY_COOLDOWN,
                        "Recovery cooldown for ${component.name}: ${delayMs}ms"
                    )
                } else {
                    logger.w(
                        LogEventId.RECOVERY_SCHEDULED,
                        "Recovery scheduled for ${component.name} in ${delayMs}ms, attempt=$attempt"
                    )
                }

                retryBackoffScheduler.pause(delayMs)
                if (!isRunning.get()) return

                logger.i(LogEventId.RECOVERY_ATTEMPT, "Recovery attempt for ${component.name}, attempt=$attempt")

                val recovered = runCatching {
                    managed.stop.invoke()
                    managed.start.invoke()
                }.fold(
                    onSuccess = { true },
                    onFailure = { error ->
                        logger.e(
                            LogEventId.RECOVERY_FAILED,
                            error,
                            "Recovery attempt failed for ${component.name}, attempt=$attempt"
                        )
                        false
                    }
                )

                if (recovered) {
                    recoveryAttempts[component] = 0
                    runtimeHealthRepository.mark(
                        component = component,
                        status = ComponentHealthStatus.HEALTHY,
                        eventId = LogEventId.RECOVERY_SUCCEEDED,
                        message = "recovery succeeded",
                        recoveryAttempts = attempt
                    )
                    watchdogManager.updateComponentStatus(
                        component = managed.watchdogKey,
                        status = WatchdogComponentStatus.HEALTHY,
                        details = "recovered"
                    )
                    watchdogManager.heartbeat(managed.watchdogKey)
                    logger.i(LogEventId.RECOVERY_SUCCEEDED, "Recovery succeeded for ${component.name}")
                    return
                }

                if (useCooldown) {
                    attempt = 0
                    recoveryAttempts[component] = 0
                }
            }
        }
    }

    private fun buildActiveComponents(
        settings: RuntimeSettings
    ): Map<RuntimeComponent, ManagedComponent> {
        val flags = settings.featureFlags
        val map = linkedMapOf<RuntimeComponent, ManagedComponent>()

        map[RuntimeComponent.SERVER] = ManagedComponent(
            component = RuntimeComponent.SERVER,
            watchdogKey = SERVER_KEY,
            timeoutMs = 25_000L,
            start = { localHttpServer.start(settings.serverPort) },
            stop = { localHttpServer.stop() },
            healthCheck = { localHttpServer.isHealthy() }
        )

        if (flags.mjpegStreaming) {
            map[RuntimeComponent.CAMERA] = ManagedComponent(
                component = RuntimeComponent.CAMERA,
                watchdogKey = CAMERA_KEY,
                timeoutMs = 25_000L,
                start = { cameraRuntime.start(activeStreamConfig) },
                stop = { cameraRuntime.stop() },
                healthCheck = { cameraRuntime.isHealthy() }
            )
        }

        if (flags.audioPushToTalk || flags.audioLive || flags.audioPlayback) {
            map[RuntimeComponent.AUDIO] = ManagedComponent(
                component = RuntimeComponent.AUDIO,
                watchdogKey = AUDIO_KEY,
                timeoutMs = 30_000L,
                start = { pushToTalkManager.start() },
                stop = { pushToTalkManager.stop() },
                healthCheck = { pushToTalkManager.isHealthy() }
            )
        }

        if (flags.loopRecording) {
            map[RuntimeComponent.STORAGE] = ManagedComponent(
                component = RuntimeComponent.STORAGE,
                watchdogKey = STORAGE_KEY,
                timeoutMs = 40_000L,
                start = { loopRecordingManager.start(settings.recording) },
                stop = { loopRecordingManager.stop() },
                healthCheck = { loopRecordingManager.isHealthy() }
            )
        }

        return map
    }

    private fun startOrder(): List<RuntimeComponent> = listOf(
        RuntimeComponent.SERVER,
        RuntimeComponent.CAMERA,
        RuntimeComponent.AUDIO,
        RuntimeComponent.STORAGE
    )

    private fun stopOrder(): List<RuntimeComponent> = startOrder().asReversed()

    private data class ManagedComponent(
        val component: RuntimeComponent,
        val watchdogKey: String,
        val timeoutMs: Long,
        val start: suspend () -> Unit,
        val stop: suspend () -> Unit,
        val healthCheck: suspend () -> Boolean
    )

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 2_000L
        const val WATCHDOG_TIMEOUT_MS = WatchdogComponentHealth.DEFAULT_HEARTBEAT_TIMEOUT_MS

        const val WATCHDOG_KEY = "watchdog"
        const val SERVER_KEY = "server"
        const val CAMERA_KEY = "camera"
        const val AUDIO_KEY = "audio"
        const val STORAGE_KEY = "storage"
    }
}
