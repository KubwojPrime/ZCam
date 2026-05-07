package com.zcam.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zcam.client.ClientCallResult
import com.zcam.client.ClientTarget
import com.zcam.client.LocalClient
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.logging.ZCamLogger
import com.zcam.service.ZCamForegroundService
import com.zcam.service.runtime.ComponentHealth
import com.zcam.service.runtime.ComponentHealthStatus
import com.zcam.service.runtime.RuntimeHealthRepository
import com.zcam.service.runtime.RuntimeOverallStatus
import com.zcam.ui.ComponentStatusUi
import com.zcam.ui.PairingUiState
import com.zcam.ui.StatusTone
import com.zcam.ui.ZCamMode
import com.zcam.ui.ZCamScreen
import com.zcam.ui.ZCamUiAction
import com.zcam.ui.ZCamUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ZCamMainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val runtimeHealthRepository: RuntimeHealthRepository,
    private val runtimeSettingsRepository: RuntimeSettingsRepository,
    private val runtimeStateRepository: RuntimeStateRepository,
    private val localClient: LocalClient,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : ViewModel() {

    private val _state = MutableStateFlow(ZCamUiState())
    val state = _state.asStateFlow()

    private var settingsSnapshot: RuntimeSettings = RuntimeSettingsDefaults.value
    private var volumeSyncJob: Job? = null

    init {
        observeSettings()
        observeRuntimeHealth()
        observeDesiredState()
        monitorThermalStatus()
        refreshPreviewLoop()
        refreshClientStatusLoop()
    }

    fun onAction(action: ZCamUiAction) {
        when (action) {
            is ZCamUiAction.ScreenChanged -> _state.update { it.copy(screen = action.screen) }
            is ZCamUiAction.ModeChanged -> {
                _state.update {
                    it.copy(
                        mode = action.mode,
                        errorMessage = null
                    )
                }
            }

            ZCamUiAction.StartRuntime -> startRuntime()
            ZCamUiAction.StopRuntime -> stopRuntime()
            ZCamUiAction.RefreshClientStatus -> refreshClientStatusNow()
            is ZCamUiAction.ClientHostChanged -> _state.update { it.copy(clientHost = action.host.trim()) }
            is ZCamUiAction.ClientPortChanged -> _state.update { it.copy(clientPort = action.port.filter(Char::isDigit)) }

            is ZCamUiAction.PushToTalkChanged -> setPushToTalk(action.pressed)
            ZCamUiAction.ToggleLiveListen -> toggleLiveListen()
            is ZCamUiAction.PlayQuickSound -> playQuickSound(action.clipId, action.aversive)
            is ZCamUiAction.VolumeChanged -> updateVolume(action.levelPercent)

            ZCamUiAction.RequestPairingQr -> requestPairingQr()
            is ZCamUiAction.PairingPinChanged -> {
                _state.update { it.copy(pairing = it.pairing.copy(pin = action.value.filter(Char::isDigit).take(10))) }
            }
            is ZCamUiAction.PairingDeviceIdChanged -> {
                _state.update { it.copy(pairing = it.pairing.copy(deviceId = action.value.take(64))) }
            }
            is ZCamUiAction.PairingDisplayNameChanged -> {
                _state.update { it.copy(pairing = it.pairing.copy(displayName = action.value.take(64))) }
            }
            ZCamUiAction.SubmitPairing -> submitPairing()
            ZCamUiAction.ClearError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch(dispatchers.io) {
            runtimeSettingsRepository.settings.collectLatest { settings ->
                settingsSnapshot = settings
                _state.update { state ->
                    val nextPort = settings.serverPort.toString()
                    state.copy(
                        clientPort = if (state.mode == ZCamMode.SERVER) nextPort else state.clientPort
                    )
                }
            }
        }
    }

    private fun observeRuntimeHealth() {
        viewModelScope.launch(dispatchers.io) {
            runtimeHealthRepository.health.collectLatest { health ->
                _state.update { state ->
                    val runtimeTone = when (health.overall) {
                        RuntimeOverallStatus.HEALTHY -> StatusTone.HEALTHY
                        RuntimeOverallStatus.RECOVERING -> StatusTone.WARNING
                        RuntimeOverallStatus.FAILED -> StatusTone.ERROR
                        RuntimeOverallStatus.DEGRADED -> StatusTone.WARNING
                        RuntimeOverallStatus.STARTING,
                        RuntimeOverallStatus.STOPPING -> StatusTone.NEUTRAL
                        RuntimeOverallStatus.STOPPED -> StatusTone.NEUTRAL
                    }

                    val recovery = deriveRecoveryLabel(health.components.values.toList())
                    state.copy(
                        runtimeLabel = health.overall.name.lowercase().replaceFirstChar { it.uppercase() },
                        runtimeTone = runtimeTone,
                        recoveryLabel = recovery.first,
                        recoveryTone = recovery.second,
                        componentStatuses = health.components.values.map { it.toUi() }
                    )
                }
            }
        }
    }

    private fun observeDesiredState() {
        viewModelScope.launch(dispatchers.io) {
            runtimeStateRepository.desiredState.collectLatest { desired ->
                _state.update { it.copy(runtimeOn = desired.shouldRun) }
            }
        }
    }

    private fun refreshPreviewLoop() {
        viewModelScope.launch(dispatchers.io) {
            while (isActive) {
                val target = currentTargetForCommands()
                val preview = localClient.fetchSnapshot(target)
                _state.update { state ->
                    when (preview) {
                        is ClientCallResult.Success -> state.copy(
                            previewFrameJpeg = preview.value,
                            previewLabel = "Updated ${System.currentTimeMillis()}"
                        )
                        is ClientCallResult.Failure -> state.copy(
                            previewLabel = "Preview unavailable (${preview.reason})"
                        )
                    }
                }
                delay(PREVIEW_REFRESH_MS)
            }
        }
    }

    private fun refreshClientStatusLoop() {
        viewModelScope.launch(dispatchers.io) {
            while (isActive) {
                refreshClientStatusNow()
                delay(CLIENT_STATUS_REFRESH_MS)
            }
        }
    }

    private fun refreshClientStatusNow() {
        viewModelScope.launch(dispatchers.io) {
            val result = localClient.fetchStatus(currentTargetForCommands())
            _state.update { state ->
                when (result) {
                    is ClientCallResult.Success -> {
                        val s = result.value
                        state.copy(
                            clientReachable = s.alive,
                            clientStatusLabel = "alive=${s.alive} clients=${s.streamClients} video=${s.videoRunning}"
                        )
                    }
                    is ClientCallResult.Failure -> {
                        state.copy(
                            clientReachable = false,
                            clientStatusLabel = "unreachable (${result.reason})"
                        )
                    }
                }
            }
        }
    }

    private fun monitorThermalStatus() {
        viewModelScope.launch(dispatchers.io) {
            while (isActive) {
                val (label, tone) = readThermalState()
                _state.update {
                    it.copy(
                        thermalLabel = label,
                        thermalTone = tone
                    )
                }
                delay(THERMAL_REFRESH_MS)
            }
        }
    }

    private fun startRuntime() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(working = true, errorMessage = null) }
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    ZCamForegroundService.startIntent(appContext)
                )
            }.onFailure { error ->
                logger.w("Start runtime failed: ${error.message}")
                _state.update { it.copy(errorMessage = "Failed to start runtime: ${error.message}") }
            }
            _state.update { it.copy(working = false) }
        }
    }

    private fun stopRuntime() {
        viewModelScope.launch(dispatchers.io) {
            _state.update { it.copy(working = true, errorMessage = null) }
            runCatching {
                appContext.startService(ZCamForegroundService.stopIntent(appContext))
            }.onFailure { error ->
                logger.w("Stop runtime failed: ${error.message}")
                _state.update { it.copy(errorMessage = "Failed to stop runtime: ${error.message}") }
            }
            _state.update { it.copy(working = false) }
        }
    }

    private fun setPushToTalk(pressed: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            val result = localClient.setPushToTalk(currentTargetForCommands(), enabled = pressed)
            _state.update { state ->
                when (result) {
                    is ClientCallResult.Success -> state.copy(pttPressed = pressed, errorMessage = null)
                    is ClientCallResult.Failure -> state.copy(errorMessage = "Push-to-talk failed: ${result.reason}")
                }
            }
        }
    }

    private fun toggleLiveListen() {
        viewModelScope.launch(dispatchers.io) {
            val enabled = !state.value.liveListenEnabled
            val result = localClient.setLiveListen(currentTargetForCommands(), enabled = enabled)
            _state.update { current ->
                when (result) {
                    is ClientCallResult.Success -> current.copy(liveListenEnabled = enabled, errorMessage = null)
                    is ClientCallResult.Failure -> current.copy(errorMessage = "Live listen failed: ${result.reason}")
                }
            }
        }
    }

    private fun playQuickSound(clipId: String, aversive: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            when (val result = localClient.playQuickSound(currentTargetForCommands(), clipId, aversive)) {
                is ClientCallResult.Success -> _state.update { it.copy(errorMessage = null) }
                is ClientCallResult.Failure -> _state.update {
                    it.copy(errorMessage = "Playback failed: ${result.reason}")
                }
            }
        }
    }

    private fun updateVolume(levelPercent: Int) {
        val normalized = levelPercent.coerceIn(0, 85)
        _state.update { it.copy(volumePercent = normalized) }
        volumeSyncJob?.cancel()
        volumeSyncJob = viewModelScope.launch(dispatchers.io) {
            delay(VOLUME_DEBOUNCE_MS)
            when (val result = localClient.setVolume(currentTargetForCommands(), normalized)) {
                is ClientCallResult.Success -> _state.update { it.copy(errorMessage = null) }
                is ClientCallResult.Failure -> _state.update {
                    it.copy(errorMessage = "Volume update failed: ${result.reason}")
                }
            }
        }
    }

    private fun requestPairingQr() {
        viewModelScope.launch(dispatchers.io) {
            _state.update {
                it.copy(
                    pairing = it.pairing.copy(loading = true, resultMessage = "", resultTone = StatusTone.NEUTRAL)
                )
            }
            when (val result = localClient.fetchPairingQr(currentTargetForCommands())) {
                is ClientCallResult.Success -> _state.update { state ->
                    state.copy(
                        pairing = state.pairing.copy(
                            loading = false,
                            sessionId = result.value.sessionId,
                            pairingCode = result.value.pairingCode,
                            qrPayload = result.value.qrPayload,
                            expiresAtEpochMs = result.value.expiresAtEpochMs,
                            resultMessage = "Pairing challenge ready",
                            resultTone = StatusTone.HEALTHY
                        )
                    )
                }
                is ClientCallResult.Failure -> _state.update { state ->
                    state.copy(
                        pairing = state.pairing.copy(
                            loading = false,
                            resultMessage = "QR fetch failed: ${result.reason}",
                            resultTone = StatusTone.ERROR
                        ),
                        errorMessage = "QR fetch failed: ${result.reason}"
                    )
                }
            }
        }
    }

    private fun submitPairing() {
        viewModelScope.launch(dispatchers.io) {
            val pairing = state.value.pairing
            if (pairing.pin.isBlank() || pairing.sessionId.isBlank() || pairing.pairingCode.isBlank()) {
                _state.update { it.copy(errorMessage = "PIN, sessionId and pairingCode are required.") }
                return@launch
            }

            _state.update {
                it.copy(pairing = it.pairing.copy(loading = true, resultMessage = "", resultTone = StatusTone.NEUTRAL))
            }
            when (
                val result = localClient.pairDevice(
                    target = currentTargetForCommands(),
                    pin = pairing.pin,
                    sessionId = pairing.sessionId,
                    pairingCode = pairing.pairingCode,
                    deviceId = pairing.deviceId.ifBlank { defaultDeviceId() },
                    displayName = pairing.displayName.ifBlank { "Android Client" }
                )
            ) {
                is ClientCallResult.Success -> _state.update { state ->
                    state.copy(
                        pairing = state.pairing.copy(
                            loading = false,
                            issuedToken = result.value.tokenValue,
                            resultMessage = "Paired successfully (token: ${result.value.tokenId})",
                            resultTone = StatusTone.HEALTHY
                        ),
                        errorMessage = null
                    )
                }
                is ClientCallResult.Failure -> _state.update { state ->
                    state.copy(
                        pairing = state.pairing.copy(
                            loading = false,
                            resultMessage = "Pairing failed: ${result.reason}",
                            resultTone = StatusTone.ERROR
                        ),
                        errorMessage = "Pairing failed: ${result.reason}"
                    )
                }
            }
        }
    }

    private fun currentTargetForCommands(): ClientTarget {
        val currentState = state.value
        return if (currentState.mode == ZCamMode.SERVER) {
            ClientTarget(
                host = "127.0.0.1",
                port = settingsSnapshot.serverPort,
                token = settingsSnapshot.security.apiToken,
                deviceId = currentState.pairing.deviceId.ifBlank { defaultDeviceId() }
            )
        } else {
            val parsedPort = currentState.clientPort.toIntOrNull() ?: settingsSnapshot.serverPort
            val token = currentState.pairing.issuedToken.ifBlank { null }
            ClientTarget(
                host = currentState.clientHost.ifBlank { "127.0.0.1" },
                port = parsedPort.coerceIn(1024, 65535),
                token = token,
                deviceId = currentState.pairing.deviceId.ifBlank { defaultDeviceId() }
            )
        }
    }

    private suspend fun readThermalState(): Pair<String, StatusTone> = withContext(dispatchers.io) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return@withContext "Unsupported (API < 29)" to StatusTone.NEUTRAL
        }
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@withContext "Unavailable" to StatusTone.WARNING

        val status = powerManager.currentThermalStatus
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal" to StatusTone.HEALTHY
            PowerManager.THERMAL_STATUS_LIGHT -> "Light throttling" to StatusTone.HEALTHY
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate throttling" to StatusTone.WARNING
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe throttling" to StatusTone.WARNING
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical thermal" to StatusTone.ERROR
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency thermal" to StatusTone.ERROR
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown imminent" to StatusTone.ERROR
            else -> "Unknown" to StatusTone.NEUTRAL
        }
    }

    private fun deriveRecoveryLabel(components: List<ComponentHealth>): Pair<String, StatusTone> {
        val recovering = components.firstOrNull { it.status == ComponentHealthStatus.RECOVERING }
        if (recovering != null) {
            return "Recovering ${recovering.component.name.lowercase()}" to StatusTone.WARNING
        }
        val failed = components.firstOrNull { it.status == ComponentHealthStatus.FAILED }
        if (failed != null) {
            return "Failed ${failed.component.name.lowercase()}" to StatusTone.ERROR
        }
        return "No active recovery" to StatusTone.NEUTRAL
    }

    private fun ComponentHealth.toUi(): ComponentStatusUi {
        val tone = when (status) {
            ComponentHealthStatus.HEALTHY -> StatusTone.HEALTHY
            ComponentHealthStatus.RECOVERING,
            ComponentHealthStatus.STARTING,
            ComponentHealthStatus.STOPPING -> StatusTone.WARNING
            ComponentHealthStatus.FAILED -> StatusTone.ERROR
            ComponentHealthStatus.IDLE,
            ComponentHealthStatus.STOPPED -> StatusTone.NEUTRAL
        }
        return ComponentStatusUi(
            label = component.name.lowercase().replaceFirstChar { it.uppercase() },
            status = status.name,
            details = lastMessage,
            recoveryAttempts = recoveryAttempts,
            tone = tone
        )
    }

    private fun defaultDeviceId(): String {
        val model = Build.MODEL?.trim().orEmpty().replace(Regex("[^A-Za-z0-9._:-]"), "_")
        return if (model.isBlank()) "android-client" else "android-$model"
    }

    private companion object {
        const val PREVIEW_REFRESH_MS = 1_200L
        const val CLIENT_STATUS_REFRESH_MS = 2_000L
        const val THERMAL_REFRESH_MS = 2_500L
        const val VOLUME_DEBOUNCE_MS = 180L
    }
}
