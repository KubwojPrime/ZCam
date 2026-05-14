package com.zcam.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zcam.camera.RearCameraLensCatalog
import com.zcam.camera.RearCameraLensDetector
import com.zcam.client.ClientCallResult
import com.zcam.client.ClientTarget
import com.zcam.client.LocalAudioTransport
import com.zcam.client.LocalAudioTransportResult
import com.zcam.client.LocalClient
import com.zcam.core.device.PowerStatusProvider
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.PreviewProfile
import com.zcam.core.domain.config.PreviewTransport
import com.zcam.core.domain.config.RearCameraLens
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.ClientSession
import com.zcam.core.domain.settings.ClientSessionRepository
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import com.zcam.core.domain.settings.RuntimeStateRepository
import com.zcam.core.logging.ZCamLogger
import com.zcam.security.PairingClientType
import com.zcam.security.PendingPairingRequest
import com.zcam.security.SecurityManager
import com.zcam.service.ZCamForegroundService
import com.zcam.service.runtime.ComponentHealth
import com.zcam.service.runtime.ComponentHealthStatus
import com.zcam.service.runtime.RuntimeHealthRepository
import com.zcam.service.runtime.RuntimeOverallStatus
import com.zcam.ui.ComponentStatusUi
import com.zcam.ui.PendingPairingRequestUi
import com.zcam.ui.RecordingEventUi
import com.zcam.ui.SettingsUiState
import com.zcam.ui.StatusTone
import com.zcam.ui.TrustedDeviceUi
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
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ZCamMainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val runtimeHealthRepository: RuntimeHealthRepository,
    private val runtimeSettingsRepository: RuntimeSettingsRepository,
    private val runtimeStateRepository: RuntimeStateRepository,
    private val clientSessionRepository: ClientSessionRepository,
    private val localClient: LocalClient,
    private val localAudioTransport: LocalAudioTransport,
    private val securityManager: SecurityManager,
    private val powerStatusProvider: PowerStatusProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : ViewModel() {

    private val _state = MutableStateFlow(ZCamUiState())
    val state = _state.asStateFlow()

    private var settingsSnapshot: RuntimeSettings = RuntimeSettingsDefaults.value
    private var localRearLensCatalog: RearCameraLensCatalog = RearCameraLensCatalog()
    private var volumeSyncJob: Job? = null
    private var clientSessionSyncJob: Job? = null
    private var playbackLoadJob: Job? = null
    private var recordingDownloadJob: Job? = null

    init {
        observeSettings()
        observeClientSession()
        observePendingPairingRequests()
        observeRuntimeHealth()
        observeDesiredState()
        monitorLocalPowerStatus()
        monitorThermalStatus()
        refreshLocalRearLensCatalog()
        refreshPreviewTarget()
        refreshClientStatusLoop()
        refreshPreviewSnapshotLoop()
    }

    fun onAction(action: ZCamUiAction) {
        when (action) {
            is ZCamUiAction.ScreenChanged -> {
                _state.update { it.copy(screen = action.screen) }
                if (action.screen == ZCamScreen.RECORDINGS && state.value.mode == ZCamMode.CLIENT) {
                    ensureRecordingsDefaults()
                    if (state.value.recordings.items.isEmpty()) {
                        fetchRecordingsForRange()
                    }
                }
            }
            is ZCamUiAction.ModeChanged -> {
                if (action.mode != ZCamMode.CLIENT) {
                    viewModelScope.launch(dispatchers.io) {
                        localAudioTransport.stopAll()
                    }
                    playbackLoadJob?.cancel()
                    recordingDownloadJob?.cancel()
                }
                _state.update { current ->
                    current.copy(
                        mode = action.mode,
                        showModePicker = false,
                        screen = if (current.showModePicker) ZCamScreen.MAIN else current.screen,
                        showPairingSuggestionDialog = false,
                        errorMessage = null
                    )
                }
                scheduleClientSessionPersist()
                refreshServerLanHost()
                refreshPreviewTarget()
            }
            ZCamUiAction.OpenModePicker -> _state.update {
                it.copy(
                    showModePicker = true,
                    screen = ZCamScreen.MAIN,
                    showPairingSuggestionDialog = false,
                    errorMessage = null
                )
            }

            ZCamUiAction.RequestPermissions -> Unit
            ZCamUiAction.StartRuntime -> startRuntime()
            ZCamUiAction.StopRuntime -> stopRuntime()
            ZCamUiAction.RefreshClientStatus -> refreshClientStatusNow()
            is ZCamUiAction.ClientHostChanged -> {
                _state.update { it.copy(clientHost = action.host.trim()) }
                scheduleClientSessionPersist()
                refreshPreviewTarget()
            }
            is ZCamUiAction.ClientPortChanged -> {
                _state.update { it.copy(clientPort = action.port.filter(Char::isDigit)) }
                scheduleClientSessionPersist()
                refreshPreviewTarget()
            }

            is ZCamUiAction.SetTorchEnabled -> setTorchEnabled(action.enabled)
            is ZCamUiAction.SetNightModeEnabled -> setNightModeEnabled(action.enabled)
            is ZCamUiAction.AdjustClientZoom -> adjustClientZoom(action.deltaLinear)
            ZCamUiAction.ResetClientZoom -> resetClientZoom()
            is ZCamUiAction.PushToTalkChanged -> setPushToTalk(action.pressed)
            ZCamUiAction.ToggleLiveListen -> toggleLiveListen()
            is ZCamUiAction.PlayQuickSound -> playQuickSound(action.clipId, action.aversive)
            is ZCamUiAction.VolumeChanged -> updateVolume(action.levelPercent)
            is ZCamUiAction.RecordingsFromChanged -> _state.update {
                it.copy(recordings = it.recordings.copy(fromInput = action.value))
            }
            is ZCamUiAction.RecordingsToChanged -> _state.update {
                it.copy(recordings = it.recordings.copy(toInput = action.value))
            }
            ZCamUiAction.FetchRecordings -> fetchRecordingsForRange()
            is ZCamUiAction.PlayRecording -> playRecording(action.fileName, action.seekToEpochMs)
            is ZCamUiAction.DownloadRecording -> downloadRecordingToDevice(action.fileName)

            ZCamUiAction.RequestPairingQr -> requestPairingQr()
            is ZCamUiAction.PairingPayloadChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(payloadInput = action.value))
            }

            ZCamUiAction.ApplyPairingPayload -> applyPairingPayloadFromInput()
            is ZCamUiAction.PairingDeviceIdChanged -> {
                _state.update { it.copy(pairing = it.pairing.copy(deviceId = action.value.take(64))) }
                scheduleClientSessionPersist()
            }
            is ZCamUiAction.PairingDisplayNameChanged -> {
                _state.update { it.copy(pairing = it.pairing.copy(displayName = action.value.take(64))) }
                scheduleClientSessionPersist()
            }
            is ZCamUiAction.PairingVerificationCodeChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(verificationCodeInput = action.value.filter(Char::isDigit).take(PAIRING_CODE_DIGITS)))
            }
            ZCamUiAction.StartPairingRequest -> startPairingRequest()
            ZCamUiAction.SubmitPairing -> submitPairing()
            is ZCamUiAction.CancelPendingPairing -> cancelPendingPairing(action.requestId)
            is ZCamUiAction.SettingsServerPortChanged -> updateSettingsDraft { copy(serverPortInput = action.value.filter(Char::isDigit).take(5)) }
            is ZCamUiAction.SettingsStreamWidthChanged -> updateSettingsDraft { copy(streamWidthInput = action.value.filter(Char::isDigit).take(5)) }
            is ZCamUiAction.SettingsStreamHeightChanged -> updateSettingsDraft { copy(streamHeightInput = action.value.filter(Char::isDigit).take(5)) }
            is ZCamUiAction.SettingsStreamFpsChanged -> updateSettingsDraft { copy(streamFpsInput = action.value.filter(Char::isDigit).take(2)) }
            is ZCamUiAction.SettingsRearLensChanged -> updateSettingsDraft { copy(rearLensSelection = action.value) }
            is ZCamUiAction.SettingsPreviewTransportChanged -> updateSettingsDraft {
                val appliedProfile = previewProfileSelection?.toConfig(action.value)
                copy(
                    previewTransportSelection = action.value,
                    previewWidthInput = appliedProfile?.resolution?.width?.toString() ?: previewWidthInput,
                    previewHeightInput = appliedProfile?.resolution?.height?.toString() ?: previewHeightInput,
                    previewFpsInput = appliedProfile?.fps?.toString() ?: previewFpsInput,
                    previewBitrateKbpsInput = appliedProfile?.bitrateKbps?.toString() ?: previewBitrateKbpsInput
                )
            }
            is ZCamUiAction.SettingsPreviewProfileSelected -> updateSettingsDraft {
                val preset = action.value.toConfig(previewTransportSelection)
                copy(
                    previewProfileSelection = action.value,
                    previewWidthInput = preset.resolution.width.toString(),
                    previewHeightInput = preset.resolution.height.toString(),
                    previewFpsInput = preset.fps.toString(),
                    previewBitrateKbpsInput = preset.bitrateKbps.toString()
                )
            }
            is ZCamUiAction.SettingsPreviewWidthChanged -> updateSettingsDraft {
                copy(
                    previewWidthInput = action.value.filter(Char::isDigit).take(5),
                    previewProfileSelection = null
                )
            }
            is ZCamUiAction.SettingsPreviewHeightChanged -> updateSettingsDraft {
                copy(
                    previewHeightInput = action.value.filter(Char::isDigit).take(5),
                    previewProfileSelection = null
                )
            }
            is ZCamUiAction.SettingsPreviewFpsChanged -> updateSettingsDraft {
                copy(
                    previewFpsInput = action.value.filter(Char::isDigit).take(2),
                    previewProfileSelection = null
                )
            }
            is ZCamUiAction.SettingsPreviewBitrateChanged -> updateSettingsDraft {
                copy(
                    previewBitrateKbpsInput = action.value.filter(Char::isDigit).take(4),
                    previewProfileSelection = null
                )
            }
            is ZCamUiAction.SettingsSegmentMinutesChanged -> updateSettingsDraft { copy(segmentMinutesInput = action.value.filter(Char::isDigit).take(2)) }
            is ZCamUiAction.SettingsMaxStorageGbChanged -> updateSettingsDraft { copy(maxStorageGbInput = action.value.filter(Char::isDigit).take(3)) }
            is ZCamUiAction.SettingsMinFreeStorageGbChanged -> updateSettingsDraft { copy(minFreeStorageGbInput = action.value.filter(Char::isDigit).take(3)) }
            is ZCamUiAction.SettingsPinChanged -> updateSettingsDraft { copy(pinInput = action.value.filter(Char::isDigit).take(10)) }
            is ZCamUiAction.SettingsApiTokenChanged -> updateSettingsDraft { copy(apiTokenInput = action.value.trim().take(128)) }
            is ZCamUiAction.SettingsFlagChanged -> updateSettingsFeatureFlag(action.flag, action.enabled)
            is ZCamUiAction.RevokeTrustedDevice -> revokeTrustedDevice(action.deviceId)
            ZCamUiAction.SaveSettings -> saveSettings()
            ZCamUiAction.OpenPairingFromSuggestion -> _state.update {
                it.copy(
                    screen = ZCamScreen.PAIRING,
                    showPairingSuggestionDialog = false
                )
            }
            ZCamUiAction.DismissPairingSuggestion -> _state.update { it.copy(showPairingSuggestionDialog = false) }
            ZCamUiAction.ClearError -> _state.update { it.copy(errorMessage = null) }
        }
    }

    fun onExternalPairingPayload(payload: String) {
        viewModelScope.launch(dispatchers.io) {
            _state.update { current ->
                current.copy(
                    mode = ZCamMode.CLIENT,
                    showModePicker = false,
                    screen = ZCamScreen.PAIRING,
                    pairing = current.pairing.copy(payloadInput = payload)
                )
            }
            persistClientSession()
            applyPairingPayloadFromInput()
        }
    }

    private fun observeSettings() {
        viewModelScope.launch(dispatchers.io) {
            runtimeSettingsRepository.settings.collectLatest { settings ->
                val detectedCatalog = detectLocalRearLensCatalog()
                localRearLensCatalog = detectedCatalog
                settingsSnapshot = settings
                _state.update { state ->
                    val nextPort = settings.serverPort.toString()
                    state.copy(
                        clientPort = if (state.mode == ZCamMode.SERVER || state.clientPort.isBlank()) {
                            nextPort
                        } else {
                            state.clientPort
                        },
                        settings = settings.toUiSettings(
                            previous = state.settings,
                            ultraWideLensAvailable = detectedCatalog.ultraWideAvailable
                        ),
                        previewTransport = if (state.mode == ZCamMode.SERVER) {
                            settings.stream.preview.transport
                        } else {
                            state.previewTransport
                        },
                        previewTransportLabel = if (state.mode == ZCamMode.SERVER) {
                            previewTransportLabel(
                                transport = settings.stream.preview.transport,
                                usingFallback = false
                            )
                        } else {
                            state.previewTransportLabel
                        },
                        previewDiagnosticsLabel = if (state.mode == ZCamMode.SERVER) {
                            previewDiagnosticsLabel(
                                width = settings.stream.preview.resolution.width,
                                height = settings.stream.preview.resolution.height,
                                targetFps = settings.stream.preview.fps,
                                targetBitrateKbps = settings.stream.preview.bitrateKbps,
                                estimatedBitrateKbps = 0,
                                sentFps = 0
                            )
                        } else {
                            state.previewDiagnosticsLabel
                        },
                        ultraWideAvailable = if (state.mode == ZCamMode.SERVER) {
                            detectedCatalog.ultraWideAvailable
                        } else {
                            state.ultraWideAvailable
                        },
                        cameraLensLabel = if (state.mode == ZCamMode.SERVER && !state.runtimeOn) {
                            "Lens: ${settings.stream.rearLens.label} selected"
                        } else {
                            state.cameraLensLabel
                        },
                        cameraLensTone = if (state.mode == ZCamMode.SERVER && !state.runtimeOn) {
                            StatusTone.NEUTRAL
                        } else {
                            state.cameraLensTone
                        }
                    )
                }
                refreshServerLanHost()
                refreshPreviewTarget()
            }
        }
    }

    private fun observeClientSession() {
        viewModelScope.launch(dispatchers.io) {
            clientSessionRepository.session.collectLatest { session ->
                _state.update { current ->
                    val restoredMode = session.lastModeName.toStoredModeOrNull()
                    val shouldRestoreMode = current.showModePicker && restoredMode != null
                    current.copy(
                        mode = if (shouldRestoreMode) restoredMode!! else current.mode,
                        showModePicker = if (shouldRestoreMode) false else current.showModePicker,
                        screen = if (shouldRestoreMode) ZCamScreen.MAIN else current.screen,
                        clientHost = if (session.serverHost.isNotBlank()) session.serverHost else current.clientHost,
                        clientPort = if (session.serverPort in 1..65535) session.serverPort.toString() else current.clientPort,
                        pairing = current.pairing.copy(
                            deviceId = session.deviceId.ifBlank { current.pairing.deviceId },
                            displayName = session.displayName.ifBlank { current.pairing.displayName },
                            issuedToken = session.issuedToken.ifBlank { current.pairing.issuedToken }
                        )
                    )
                }
                refreshPreviewTarget()
            }
        }
    }

    private fun observePendingPairingRequests() {
        viewModelScope.launch(dispatchers.io) {
            securityManager.pendingPairingRequests.collectLatest { requests ->
                _state.update { current ->
                    current.copy(
                        pendingPairingRequests = requests.map { request -> request.toUi() }
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
                refreshPreviewTarget()
            }
        }
    }

    private fun refreshPreviewTarget() {
        val current = state.value
        val target = currentTargetForCommands()
        val mjpegUrl = when (current.mode) {
            ZCamMode.SERVER -> if (current.runtimeOn) {
                localClient.buildPreviewStreamUrl(target)
            } else {
                ""
            }
            ZCamMode.CLIENT -> if (current.clientHost.isNotBlank()) {
                localClient.buildPreviewStreamUrl(target)
            } else {
                ""
            }
        }
        val h264Url = when (current.mode) {
            ZCamMode.SERVER -> if (current.runtimeOn) {
                localClient.buildPreviewH264SocketUrl(target)
            } else {
                ""
            }
            ZCamMode.CLIENT -> if (current.clientHost.isNotBlank()) {
                localClient.buildPreviewH264SocketUrl(target)
            } else {
                ""
            }
        }
        val streamUrl = when (current.mode) {
            ZCamMode.SERVER,
            ZCamMode.CLIENT -> if (current.previewTransport == PreviewTransport.H264) h264Url else mjpegUrl
        }
        val previewLabel = when {
            streamUrl.isNotBlank() && current.previewStateTone == StatusTone.WARNING -> "Preview reconnecting..."
            streamUrl.isNotBlank() && current.previewStateTone == StatusTone.ERROR -> "Preview unavailable"
            streamUrl.isBlank() && current.mode == ZCamMode.SERVER && !current.runtimeOn -> "Start runtime to see live preview."
            streamUrl.isBlank() && current.mode == ZCamMode.CLIENT && current.clientHost.isBlank() -> "Set server host to see preview."
            streamUrl.isBlank() && current.mode == ZCamMode.CLIENT -> "Preview unavailable"
            else -> ""
        }
        _state.update {
            it.copy(
                previewStreamUrl = streamUrl,
                previewMjpegFallbackUrl = mjpegUrl,
                previewLabel = previewLabel,
                previewFrameJpeg = if (streamUrl.isBlank()) null else it.previewFrameJpeg
            )
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

    private fun refreshPreviewSnapshotLoop() {
        viewModelScope.launch(dispatchers.io) {
            while (isActive) {
                val current = state.value
                if (!shouldRefreshPreviewSnapshots(current)) {
                    if (current.previewFrameJpeg != null) {
                        _state.update { it.copy(previewFrameJpeg = null) }
                    }
                    delay(PREVIEW_SNAPSHOT_REFRESH_MS)
                    continue
                }

                when (val result = localClient.fetchSnapshot(currentTargetForCommands())) {
                    is ClientCallResult.Success -> _state.update { latest ->
                        if (shouldRefreshPreviewSnapshots(latest)) {
                            latest.copy(
                                previewFrameJpeg = result.value,
                                previewStateLabel = if (latest.clientReachable || latest.mode == ZCamMode.SERVER) {
                                    "Preview live"
                                } else {
                                    latest.previewStateLabel
                                },
                                previewStateTone = if (latest.clientReachable || latest.mode == ZCamMode.SERVER) {
                                    StatusTone.HEALTHY
                                } else {
                                    latest.previewStateTone
                                }
                            )
                        } else {
                            latest
                        }
                    }

                    is ClientCallResult.Failure -> _state.update { latest ->
                        if (shouldRefreshPreviewSnapshots(latest)) {
                            latest.copy(
                                previewStateLabel = if (latest.clientReachable || latest.mode == ZCamMode.SERVER) {
                                    "Preview reconnecting"
                                } else {
                                    "Preview offline"
                                },
                                previewStateTone = if (latest.clientReachable || latest.mode == ZCamMode.SERVER) {
                                    StatusTone.WARNING
                                } else {
                                    StatusTone.ERROR
                                }
                            )
                        } else {
                            latest
                        }
                    }
                }
                delay(PREVIEW_SNAPSHOT_REFRESH_MS)
            }
        }
    }

    private fun refreshClientStatusNow() {
        viewModelScope.launch(dispatchers.io) {
            val target = currentTargetForCommands()
            val result = localClient.fetchStatus(target)
            var shouldStopPushToTalk = false
            var shouldStopLiveListen = false
            var shouldRecoverLiveListen = false
            _state.update { current ->
                when (result) {
                    is ClientCallResult.Success -> {
                        val status = result.value
                        val syncVolumeFromServer = volumeSyncJob?.isActive != true
                        shouldStopPushToTalk = !status.audioTransmitting
                        shouldStopLiveListen = !status.audioLiveListening
                        shouldRecoverLiveListen = current.mode == ZCamMode.CLIENT &&
                            status.audioLiveListening &&
                            !current.liveListenEnabled
                        val serverVolume = status.audioVolumePercent
                            ?.coerceIn(
                                status.audioMinVolumePercent ?: 0,
                                status.audioMaxVolumePercent ?: 85
                            )
                        val power = powerUiState(
                            batteryPercent = status.batteryPercent,
                            charging = status.charging,
                            remote = current.mode == ZCamMode.CLIENT
                        )
                        val previewUi = when {
                            !status.alive -> "Preview offline" to StatusTone.ERROR
                            !status.videoRunning -> "Preview stopped" to StatusTone.WARNING
                            status.lastFrameAgeMs >= PREVIEW_STALE_FRAME_MS -> "Preview reconnecting" to StatusTone.WARNING
                            else -> "Preview live" to StatusTone.HEALTHY
                        }
                        val selectedPreviewTransport = when {
                            status.previewTransport == PreviewTransport.H264 && status.previewEncoderRunning -> PreviewTransport.H264
                            else -> PreviewTransport.MJPEG
                        }
                        val audioUi = when {
                            !status.alive -> "Audio unavailable" to StatusTone.ERROR
                            shouldRecoverLiveListen -> "Live listen reconnecting" to StatusTone.WARNING
                            status.audioTransmitting && !current.pttPressed -> "Push-to-talk active on server" to StatusTone.WARNING
                            status.audioTransmitting && status.audioLiveListening -> "Audio live + push-to-talk active" to StatusTone.HEALTHY
                            status.audioTransmitting -> "Push-to-talk active" to StatusTone.HEALTHY
                            status.audioLiveListening -> "Live listen active" to StatusTone.HEALTHY
                            status.audioPlayingBack -> "Audio playback active" to StatusTone.HEALTHY
                            else -> "Audio idle" to StatusTone.NEUTRAL
                        }
                        val lensUi = cameraLensUi(
                            selectedLens = status.selectedRearLens,
                            activeLens = status.activeRearLens,
                            runtimeActive = status.videoRunning
                        )
                        current.copy(
                            clientReachable = status.alive,
                            clientStatusLabel = if (status.alive) "Connected" else "Unavailable",
                            audioRuntimeLabel = audioUi.first,
                            audioRuntimeTone = audioUi.second,
                            previewTransport = selectedPreviewTransport,
                            previewTransportLabel = previewTransportLabel(
                                transport = selectedPreviewTransport,
                                usingFallback = selectedPreviewTransport != status.previewTransport
                            ),
                            previewDiagnosticsLabel = previewDiagnosticsLabel(
                                width = status.previewTargetWidth,
                                height = status.previewTargetHeight,
                                targetFps = status.previewTargetFps,
                                targetBitrateKbps = status.previewTargetBitrateKbps,
                                estimatedBitrateKbps = status.previewEstimatedBitrateKbps,
                                sentFps = status.previewSentFps,
                                error = status.previewEncoderError
                            ),
                            previewStreamUrl = if (selectedPreviewTransport == PreviewTransport.H264) {
                                localClient.buildPreviewH264SocketUrl(target)
                            } else {
                                localClient.buildPreviewStreamUrl(target)
                            },
                            previewMjpegFallbackUrl = localClient.buildPreviewStreamUrl(target),
                            cameraLensLabel = lensUi.first,
                            cameraLensTone = lensUi.second,
                            ultraWideAvailable = if (current.mode == ZCamMode.SERVER) {
                                localRearLensCatalog.ultraWideAvailable || status.ultraWideAvailable
                            } else {
                                status.ultraWideAvailable
                            },
                            clientTorchEnabled = status.torchEnabled,
                            clientNightModeEnabled = status.nightModeEnabled,
                            clientLowLightBoostSupported = status.lowLightBoostSupported,
                            clientZoomLinear = status.zoomLinear.coerceIn(0f, 1f),
                            clientZoomRatio = status.zoomRatio.coerceAtLeast(1f),
                            clientMaxZoomRatio = status.maxZoomRatio.coerceAtLeast(1f),
                            settings = current.settings.copy(
                                ultraWideLensAvailable = if (current.mode == ZCamMode.SERVER) {
                                    localRearLensCatalog.ultraWideAvailable || status.ultraWideAvailable
                                } else {
                                    current.settings.ultraWideLensAvailable
                                }
                            ),
                            pttPressed = status.audioTransmitting,
                            liveListenEnabled = status.audioLiveListening,
                            previewStateLabel = previewUi.first,
                            previewStateTone = previewUi.second,
                            serverBatteryPercent = status.batteryPercent,
                            serverCharging = status.charging,
                            serverBatteryLabel = power.label,
                            serverBatteryTone = power.tone,
                            volumePercent = if (syncVolumeFromServer && serverVolume != null) {
                                serverVolume
                            } else {
                                current.volumePercent
                            }
                        )
                    }

                    is ClientCallResult.Failure -> {
                        val failureBatteryLabel = if (current.mode == ZCamMode.CLIENT) {
                            "Server battery unavailable"
                        } else {
                            current.serverBatteryLabel
                        }
                        current.copy(
                            clientReachable = false,
                            clientStatusLabel = "Unavailable",
                            previewStateLabel = if (current.mode == ZCamMode.SERVER && !current.runtimeOn) {
                                "Preview stopped"
                            } else {
                                "Preview offline"
                            },
                            previewStateTone = if (current.mode == ZCamMode.SERVER && !current.runtimeOn) {
                                StatusTone.NEUTRAL
                            } else {
                                StatusTone.ERROR
                            },
                            previewStreamUrl = if (current.mode == ZCamMode.SERVER || current.mode == ZCamMode.CLIENT) {
                                if (current.previewTransport == PreviewTransport.H264) {
                                    localClient.buildPreviewH264SocketUrl(target)
                                } else {
                                    localClient.buildPreviewStreamUrl(target)
                                }
                            } else {
                                current.previewStreamUrl
                            },
                            previewMjpegFallbackUrl = localClient.buildPreviewStreamUrl(target),
                            audioRuntimeLabel = "Audio unavailable",
                            audioRuntimeTone = StatusTone.ERROR,
                            cameraLensLabel = if (current.mode == ZCamMode.SERVER && !current.runtimeOn) {
                                "Lens: ${settingsSnapshot.stream.rearLens.label} selected"
                            } else if (current.mode == ZCamMode.SERVER) {
                                "Lens: unavailable"
                            } else {
                                current.cameraLensLabel
                            },
                            cameraLensTone = if (current.mode == ZCamMode.SERVER && !current.runtimeOn) {
                                StatusTone.NEUTRAL
                            } else if (current.mode == ZCamMode.SERVER) {
                                StatusTone.WARNING
                            } else {
                                current.cameraLensTone
                            },
                            ultraWideAvailable = if (current.mode == ZCamMode.SERVER) {
                                localRearLensCatalog.ultraWideAvailable
                            } else {
                                current.ultraWideAvailable
                            },
                            serverBatteryPercent = if (current.mode == ZCamMode.CLIENT) null else current.serverBatteryPercent,
                            serverCharging = if (current.mode == ZCamMode.CLIENT) null else current.serverCharging,
                            serverBatteryLabel = failureBatteryLabel,
                            serverBatteryTone = if (current.mode == ZCamMode.CLIENT) StatusTone.WARNING else current.serverBatteryTone,
                            settings = current.settings.copy(
                                ultraWideLensAvailable = if (current.mode == ZCamMode.SERVER) {
                                    localRearLensCatalog.ultraWideAvailable
                                } else {
                                    current.settings.ultraWideLensAvailable
                                }
                            )
                        )
                    }
                }
            }
            if (result is ClientCallResult.Success) {
                if (shouldStopPushToTalk) {
                    localAudioTransport.stopPushToTalk()
                }
                if (shouldStopLiveListen) {
                    localAudioTransport.stopLiveListen()
                }
                if (shouldRecoverLiveListen) {
                    when (val audioResult = localAudioTransport.startLiveListen(currentTargetForCommands())) {
                        LocalAudioTransportResult.Success -> _state.update {
                            it.copy(
                                audioRuntimeLabel = "Live listen active",
                                audioRuntimeTone = StatusTone.HEALTHY,
                                errorMessage = null
                            )
                        }

                        is LocalAudioTransportResult.Failure -> _state.update {
                            it.copy(
                                audioRuntimeLabel = "Live listen reconnect failed",
                                audioRuntimeTone = StatusTone.WARNING,
                                errorMessage = "Live listen reconnect failed: ${audioResult.reason}${audioResult.detail?.let { detail -> " ($detail)" } ?: ""}"
                            )
                        }
                    }
                }
            }
            refreshPreviewTarget()
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
            var started = false
            runCatching {
                ContextCompat.startForegroundService(
                    appContext,
                    ZCamForegroundService.startIntent(appContext)
                )
                started = true
            }.onFailure { error ->
                logger.w("Start runtime failed: ${error.message}")
                _state.update { it.copy(errorMessage = "Failed to start runtime: ${error.message}") }
            }
            _state.update { current ->
                current.copy(
                    working = false,
                    showPairingSuggestionDialog = started &&
                        current.mode == ZCamMode.SERVER &&
                        settingsSnapshot.security.trustedDevices.isEmpty()
                )
            }
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
            _state.update { it.copy(working = false, showPairingSuggestionDialog = false) }
        }
    }

    private fun setPushToTalk(pressed: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            val target = currentTargetForCommands()
            if (!pressed) {
                localAudioTransport.stopPushToTalk()
            }

            when (val result = localClient.setPushToTalk(target, enabled = pressed)) {
                is ClientCallResult.Success -> {
                    if (pressed) {
                        when (val audioResult = localAudioTransport.startPushToTalk(target)) {
                            LocalAudioTransportResult.Success -> _state.update {
                                it.copy(
                                    pttPressed = true,
                                    audioRuntimeLabel = "Push-to-talk active",
                                    audioRuntimeTone = StatusTone.HEALTHY,
                                    errorMessage = null
                                )
                            }
                            is LocalAudioTransportResult.Failure -> {
                                localClient.setPushToTalk(target, enabled = false)
                                _state.update {
                                    it.copy(
                                        pttPressed = false,
                                        audioRuntimeLabel = "Push-to-talk failed",
                                        audioRuntimeTone = StatusTone.ERROR,
                                        errorMessage = "Push-to-talk transport failed: ${audioResult.reason}${audioResult.detail?.let { detail -> " ($detail)" } ?: ""}"
                                    )
                                }
                            }
                        }
                    } else {
                        _state.update {
                            it.copy(
                                pttPressed = false,
                                audioRuntimeLabel = if (it.liveListenEnabled) "Live listen active" else "Audio idle",
                                audioRuntimeTone = if (it.liveListenEnabled) StatusTone.HEALTHY else StatusTone.NEUTRAL,
                                errorMessage = null
                            )
                        }
                    }
                }
                is ClientCallResult.Failure -> {
                    _state.update {
                        it.copy(
                            audioRuntimeLabel = "Push-to-talk request failed",
                            audioRuntimeTone = StatusTone.ERROR,
                            errorMessage = "Push-to-talk failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                        )
                    }
                }
            }
        }
    }

    private fun monitorLocalPowerStatus() {
        viewModelScope.launch(dispatchers.io) {
            while (isActive) {
                val power = powerStatusProvider.snapshot()
                val powerUi = powerUiState(
                    batteryPercent = power.batteryPercent,
                    charging = power.charging,
                    remote = false
                )
                _state.update { current ->
                    if (current.mode != ZCamMode.SERVER) {
                        current
                    } else {
                        current.copy(
                            serverBatteryPercent = power.batteryPercent,
                            serverCharging = power.charging,
                            serverBatteryLabel = powerUi.label,
                            serverBatteryTone = powerUi.tone
                        )
                    }
                }
                delay(POWER_REFRESH_MS)
            }
        }
    }

    private fun toggleLiveListen() {
        viewModelScope.launch(dispatchers.io) {
            val enabled = !state.value.liveListenEnabled
            val target = currentTargetForCommands()
            if (!enabled) {
                localAudioTransport.stopLiveListen()
            }

            when (val result = localClient.setLiveListen(target, enabled = enabled)) {
                is ClientCallResult.Success -> {
                    if (enabled) {
                        when (val audioResult = localAudioTransport.startLiveListen(target)) {
                            LocalAudioTransportResult.Success -> _state.update {
                                it.copy(
                                    liveListenEnabled = true,
                                    audioRuntimeLabel = "Live listen active",
                                    audioRuntimeTone = StatusTone.HEALTHY,
                                    errorMessage = null
                                )
                            }
                            is LocalAudioTransportResult.Failure -> {
                                localClient.setLiveListen(target, enabled = false)
                                _state.update {
                                    it.copy(
                                        liveListenEnabled = false,
                                        audioRuntimeLabel = "Live listen failed",
                                        audioRuntimeTone = StatusTone.ERROR,
                                        errorMessage = "Live listen transport failed: ${audioResult.reason}${audioResult.detail?.let { detail -> " ($detail)" } ?: ""}"
                                    )
                                }
                            }
                        }
                    } else {
                        _state.update {
                            it.copy(
                                liveListenEnabled = false,
                                audioRuntimeLabel = if (it.pttPressed) "Push-to-talk active" else "Audio idle",
                                audioRuntimeTone = if (it.pttPressed) StatusTone.HEALTHY else StatusTone.NEUTRAL,
                                errorMessage = null
                            )
                        }
                    }
                }
                is ClientCallResult.Failure -> {
                    _state.update {
                        it.copy(
                            audioRuntimeLabel = "Live listen request failed",
                            audioRuntimeTone = StatusTone.ERROR,
                            errorMessage = "Live listen failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                        )
                    }
                }
            }
        }
    }

    private fun playQuickSound(clipId: String, aversive: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            when (val result = localClient.playQuickSound(currentTargetForCommands(), clipId, aversive)) {
                is ClientCallResult.Success -> _state.update { it.copy(errorMessage = null) }
                is ClientCallResult.Failure -> _state.update { it.copy(errorMessage = "Playback failed: ${result.reason}") }
            }
        }
    }

    private fun setTorchEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            when (val result = localClient.setTorch(currentTargetForCommands(), enabled)) {
                is ClientCallResult.Success -> _state.update {
                    it.copy(
                        clientTorchEnabled = enabled,
                        errorMessage = null
                    )
                }
                is ClientCallResult.Failure -> _state.update {
                    it.copy(errorMessage = "Torch update failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}")
                }
            }
        }
    }

    private fun setNightModeEnabled(enabled: Boolean) {
        viewModelScope.launch(dispatchers.io) {
            when (val result = localClient.setNightMode(currentTargetForCommands(), enabled)) {
                is ClientCallResult.Success -> _state.update {
                    it.copy(
                        clientNightModeEnabled = enabled,
                        errorMessage = null
                    )
                }
                is ClientCallResult.Failure -> _state.update {
                    it.copy(errorMessage = "Night mode update failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}")
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
                    it.copy(errorMessage = "Volume update failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}")
                }
            }
        }
    }

    private fun fetchRecordingsForRange() {
        viewModelScope.launch(dispatchers.io) {
            val current = state.value
            val fromParsed = parseRecordingTimeInput(current.recordings.fromInput)
            val toParsed = parseRecordingTimeInput(current.recordings.toInput)

            if (fromParsed == INVALID_TIME_INPUT || toParsed == INVALID_TIME_INPUT) {
                _state.update {
                    it.copy(errorMessage = "Invalid date format. Use YYYY-MM-DD HH:mm or epoch ms.")
                }
                return@launch
            }
            if (fromParsed != null && toParsed != null && fromParsed > toParsed) {
                _state.update { it.copy(errorMessage = "Recordings range invalid: from must be <= to.") }
                return@launch
            }

            _state.update {
                it.copy(
                    recordings = it.recordings.copy(
                        loading = true,
                        resultMessage = "Loading recordings...",
                        resultTone = StatusTone.NEUTRAL
                    ),
                    errorMessage = null
                )
            }

            val target = currentTargetForCommands()

            when (
                val result = localClient.fetchRecordings(
                    target = target,
                    fromEpochMs = fromParsed,
                    toEpochMs = toParsed,
                    limit = 200
                )
            ) {
                is ClientCallResult.Success -> {
                    val items = result.value.map { clip ->
                        com.zcam.ui.RecordingItemUi(
                            fileName = clip.fileName,
                            startedAtEpochMs = clip.startedAtEpochMs,
                            endedAtEpochMs = clip.endedAtEpochMs,
                            durationMs = clip.durationMs,
                            sizeBytes = clip.sizeBytes,
                            container = clip.container,
                            codec = clip.codec
                        )
                    }
                    val eventsResult = when (
                        val eventsResult = localClient.fetchRecordingEvents(
                            target = target,
                            fromEpochMs = fromParsed,
                            toEpochMs = toParsed,
                            limit = 400
                        )
                    ) {
                        is ClientCallResult.Success -> mapRecordingEvents(items, eventsResult.value) to null
                        is ClientCallResult.Failure -> emptyList<RecordingEventUi>() to eventsResult.reason
                    }
                    val events = eventsResult.first
                    val eventsFailureReason = eventsResult.second
                    _state.update {
                        val selectedFileName = it.recordings.selectedFileName
                            .takeIf { fileName -> items.any { item -> item.fileName == fileName } }
                            ?: items.firstOrNull()?.fileName.orEmpty()
                        val selectedItem = items.firstOrNull { item -> item.fileName == selectedFileName }
                        val cachedPlaybackUrl = selectedItem?.let(::cachedPlaybackUrlFor)
                        val preservedPlaybackUrl = if (selectedFileName.isNotBlank() && selectedFileName == it.recordings.selectedFileName) {
                            it.recordings.selectedPlaybackUrl.ifBlank { cachedPlaybackUrl.orEmpty() }
                        } else {
                            cachedPlaybackUrl.orEmpty()
                        }
                        val resultMessage = buildString {
                            append("Found ${items.size} recording(s)")
                            if (eventsFailureReason == null) {
                                append(" and ${events.size} event marker(s)")
                            } else {
                                append(". Event markers unavailable: $eventsFailureReason")
                            }
                        }
                        it.copy(
                            recordings = it.recordings.copy(
                                loading = false,
                                resultMessage = resultMessage,
                                resultTone = if (eventsFailureReason == null) StatusTone.HEALTHY else StatusTone.WARNING,
                                items = items,
                                events = events,
                                selectedFileName = selectedFileName,
                                selectedPlaybackUrl = preservedPlaybackUrl,
                                selectedPlaybackOffsetMs = if (selectedFileName == it.recordings.selectedFileName) {
                                    it.recordings.selectedPlaybackOffsetMs
                                } else {
                                    0L
                                },
                                selectedPlaybackSourceLabel = recordingSourceLabel(target),
                                playbackLoading = if (selectedFileName == it.recordings.selectedFileName) {
                                    it.recordings.playbackLoading
                                } else {
                                    false
                                },
                                playbackLoadingMessage = if (selectedFileName == it.recordings.selectedFileName) {
                                    it.recordings.playbackLoadingMessage
                                } else {
                                    ""
                                },
                                playbackDownloadedBytes = if (selectedFileName == it.recordings.selectedFileName) {
                                    it.recordings.playbackDownloadedBytes
                                } else {
                                    0L
                                },
                                playbackTotalBytes = if (selectedFileName == it.recordings.selectedFileName) {
                                    it.recordings.playbackTotalBytes
                                } else {
                                    null
                                }
                            ),
                            errorMessage = null
                        )
                    }
                }
                is ClientCallResult.Failure -> {
                    _state.update {
                        it.copy(
                            recordings = it.recordings.copy(
                                loading = false,
                                resultMessage = "Recordings request failed: ${result.reason}",
                                resultTone = StatusTone.ERROR
                            ),
                            errorMessage = "Recordings request failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                        )
                    }
                }
            }
        }
    }

    private fun playRecording(fileName: String, seekToEpochMs: Long?) {
        if (fileName.isBlank()) return

        val current = state.value
        val item = current.recordings.items.firstOrNull { it.fileName == fileName } ?: return
        val offsetMs = seekToEpochMs?.let { (it - item.startedAtEpochMs).coerceAtLeast(0L) } ?: 0L
        val cachedPlaybackUrl = cachedPlaybackUrlFor(item)
        val target = currentTargetForCommands()
        val playbackUrl = cachedPlaybackUrl.orEmpty()

        _state.update {
            it.copy(
                recordings = it.recordings.copy(
                    selectedFileName = fileName,
                    selectedPlaybackUrl = playbackUrl,
                    selectedPlaybackOffsetMs = offsetMs.coerceAtMost(item.durationMs),
                    selectedPlaybackSourceLabel = recordingSourceLabel(target),
                    playbackLoading = cachedPlaybackUrl == null,
                    playbackLoadingMessage = if (cachedPlaybackUrl == null) "Loading video from server..." else "",
                    playbackDownloadedBytes = if (cachedPlaybackUrl == null) 0L else item.sizeBytes,
                    playbackTotalBytes = if (cachedPlaybackUrl == null) item.sizeBytes.takeIf { it > 0L } else item.sizeBytes
                ),
                errorMessage = null
            )
        }

        if (cachedPlaybackUrl != null) {
            return
        }

        playbackLoadJob?.cancel()
        playbackLoadJob = viewModelScope.launch(dispatchers.io) {
            val cacheFile = playbackCacheFile(fileName)
            val tempFile = File(cacheFile.parentFile, "${cacheFile.name}.part")
            tempFile.parentFile?.mkdirs()
            tempFile.delete()

            val downloadResult = runCatching {
                localClient.downloadRecording(
                    target = target,
                    fileName = fileName,
                    destination = tempFile.outputStream()
                ) { downloadedBytes, totalBytes ->
                    _state.update { latest ->
                        if (latest.recordings.selectedFileName != fileName) {
                            latest
                        } else {
                            latest.copy(
                                recordings = latest.recordings.copy(
                                    playbackLoading = true,
                                    playbackLoadingMessage = "Loading video from server...",
                                    playbackDownloadedBytes = downloadedBytes,
                                    playbackTotalBytes = totalBytes ?: item.sizeBytes.takeIf { it > 0L }
                                )
                            )
                        }
                    }
                }
            }.getOrElse { error ->
                tempFile.delete()
                ClientCallResult.Failure(
                    code = null,
                    reason = "io_error",
                    responseBody = error.message
                )
            }

            when (downloadResult) {
                is ClientCallResult.Success -> {
                    tempFile.copyTo(cacheFile, overwrite = true)
                    tempFile.delete()
                    val completedUrl = Uri.fromFile(cacheFile).toString()
                    _state.update { latest ->
                        if (latest.recordings.selectedFileName != fileName) {
                            latest
                        } else {
                            latest.copy(
                                recordings = latest.recordings.copy(
                                    selectedPlaybackUrl = completedUrl,
                                    playbackLoading = false,
                                    playbackLoadingMessage = "",
                                    playbackDownloadedBytes = item.sizeBytes,
                                    playbackTotalBytes = item.sizeBytes
                                ),
                                errorMessage = null
                            )
                        }
                    }
                }

                is ClientCallResult.Failure -> {
                    tempFile.delete()
                    val fallbackUrl = localClient.buildRecordingPlaybackUrl(target, fileName)
                    _state.update { latest ->
                        if (latest.recordings.selectedFileName != fileName) {
                            latest
                        } else {
                            latest.copy(
                                recordings = latest.recordings.copy(
                                    selectedPlaybackUrl = fallbackUrl,
                                    playbackLoading = false,
                                    playbackLoadingMessage = "Video load failed. Falling back to direct playback.",
                                    playbackDownloadedBytes = 0L,
                                    playbackTotalBytes = null
                                ),
                                errorMessage = "Playback load failed: ${downloadResult.reason}${downloadResult.responseBody?.let { body -> " ($body)" } ?: ""}"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun adjustClientZoom(deltaLinear: Float) {
        val targetZoom = (state.value.clientZoomLinear + deltaLinear).coerceIn(0f, 1f)
        applyClientZoom(targetZoom)
    }

    private fun resetClientZoom() {
        applyClientZoom(0f)
    }

    private fun applyClientZoom(linearZoom: Float) {
        viewModelScope.launch(dispatchers.io) {
            val normalizedZoom = linearZoom.coerceIn(0f, 1f)
            when (val result = localClient.setZoomLinear(currentTargetForCommands(), normalizedZoom)) {
                is ClientCallResult.Success -> _state.update {
                    val maxZoomRatio = it.clientMaxZoomRatio.coerceAtLeast(1f)
                    val approximatedRatio = 1f + ((maxZoomRatio - 1f) * normalizedZoom)
                    it.copy(
                        clientZoomLinear = normalizedZoom,
                        clientZoomRatio = approximatedRatio.coerceAtLeast(1f),
                        errorMessage = null
                    )
                }

                is ClientCallResult.Failure -> _state.update {
                    it.copy(
                        errorMessage = "Zoom update failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                    )
                }
            }
        }
    }

    private fun downloadRecordingToDevice(fileName: String) {
        if (fileName.isBlank()) return
        val item = state.value.recordings.items.firstOrNull { it.fileName == fileName } ?: return
        val target = currentTargetForCommands()

        recordingDownloadJob?.cancel()
        recordingDownloadJob = viewModelScope.launch(dispatchers.io) {
            _state.update {
                it.copy(
                    recordings = it.recordings.copy(
                        activeDownloadFileName = fileName,
                        downloadLoading = true,
                        downloadMessage = "Downloading ${item.fileName}...",
                        downloadDownloadedBytes = 0L,
                        downloadTotalBytes = item.sizeBytes.takeIf { size -> size > 0L }
                    ),
                    errorMessage = null
                )
            }

            val destination = createRecordingDownloadDestination(item)
            if (destination == null) {
                _state.update {
                    it.copy(
                        recordings = it.recordings.copy(
                            activeDownloadFileName = "",
                            downloadLoading = false,
                            downloadMessage = "Download failed: unable to create destination.",
                            downloadDownloadedBytes = 0L,
                            downloadTotalBytes = null
                        ),
                        errorMessage = "Download failed: unable to create destination."
                    )
                }
                return@launch
            }

            val cachedFile = playbackCacheFile(fileName)
                .takeIf { it.exists() && it.length() > 0L }

            val result = runCatching {
                destination.openOutputStream().use { output ->
                    if (output == null) {
                        return@use ClientCallResult.Failure(code = null, reason = "destination_unavailable")
                    }
                    if (cachedFile != null) {
                        copyLocalFileWithProgress(cachedFile, output) { copiedBytes, totalBytes ->
                            _state.update { latest ->
                                latest.copy(
                                    recordings = latest.recordings.copy(
                                        activeDownloadFileName = fileName,
                                        downloadLoading = true,
                                        downloadMessage = "Downloading ${destination.displayName}...",
                                        downloadDownloadedBytes = copiedBytes,
                                        downloadTotalBytes = totalBytes
                                    )
                                )
                            }
                        }
                        ClientCallResult.Success(Unit)
                    } else {
                        localClient.downloadRecording(
                            target = target,
                            fileName = fileName,
                            destination = output
                        ) { downloadedBytes, totalBytes ->
                            _state.update { latest ->
                                latest.copy(
                                    recordings = latest.recordings.copy(
                                        activeDownloadFileName = fileName,
                                        downloadLoading = true,
                                        downloadMessage = "Downloading ${destination.displayName}...",
                                        downloadDownloadedBytes = downloadedBytes,
                                        downloadTotalBytes = totalBytes ?: item.sizeBytes.takeIf { size -> size > 0L }
                                    )
                                )
                            }
                        }
                    }
                }
            }.getOrElse { error ->
                ClientCallResult.Failure(
                    code = null,
                    reason = "io_error",
                    responseBody = error.message
                )
            }

            when (result) {
                is ClientCallResult.Success -> {
                    destination.commit()
                    _state.update {
                        it.copy(
                            recordings = it.recordings.copy(
                                activeDownloadFileName = "",
                                downloadLoading = false,
                                downloadMessage = "Saved ${destination.displayName}",
                                downloadDownloadedBytes = item.sizeBytes,
                                downloadTotalBytes = item.sizeBytes,
                                resultMessage = "Saved ${destination.displayName}",
                                resultTone = StatusTone.HEALTHY
                            ),
                            errorMessage = null
                        )
                    }
                }

                is ClientCallResult.Failure -> {
                    destination.abort()
                    _state.update {
                        it.copy(
                            recordings = it.recordings.copy(
                                activeDownloadFileName = "",
                                downloadLoading = false,
                                downloadMessage = "Download failed: ${result.reason}",
                                downloadDownloadedBytes = 0L,
                                downloadTotalBytes = null
                            ),
                            errorMessage = "Download failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                        )
                    }
                }
            }
        }
    }

    private fun requestPairingQr() {
        val hostPort = preferredPairingHostPort()
        if (hostPort.isBlank()) {
            _state.update { it.copy(errorMessage = "LAN host unavailable. Connect server device to Wi-Fi or set server port first.") }
            return
        }
        val payload = "zcam://pair?host=${Uri.encode(hostPort)}"
        _state.update {
            it.copy(
                pairing = it.pairing.copy(
                    qrPayload = payload,
                    payloadInput = payload,
                    resolvedHostPort = hostPort,
                    sourceLabel = "Server connection QR ready",
                    resultMessage = "Scan this QR on the client to fill server address",
                    resultTone = StatusTone.HEALTHY
                ),
                errorMessage = null
            )
        }
    }

    private fun applyPairingPayloadFromInput() {
        val rawPayload = state.value.pairing.payloadInput.trim()
        if (rawPayload.isBlank()) {
            _state.update { it.copy(errorMessage = "Pairing payload is empty.") }
            return
        }

        val normalizedPayload = normalizePairingPayloadHost(rawPayload, preferredPairingHostPort())
        val parsed = parsePairingPayload(normalizedPayload)
        if (parsed == null) {
            _state.update {
                it.copy(errorMessage = "Unable to parse pairing payload. Expected: zcam://pair?host=...")
            }
            return
        }

        _state.update { current ->
            val parsedHost = parsed.host
            val nextHost = when {
                current.mode != ZCamMode.CLIENT -> current.clientHost
                parsedHost.isNullOrBlank() -> current.clientHost
                isLoopbackHost(parsedHost) && current.clientHost.isNotBlank() -> current.clientHost
                else -> parsedHost
            }
            current.copy(
                clientHost = if (current.mode == ZCamMode.CLIENT) nextHost else current.clientHost,
                clientPort = if (current.mode == ZCamMode.CLIENT && parsed.port != null) parsed.port.toString() else current.clientPort,
                pairing = current.pairing.copy(
                    resolvedHostPort = parsed.hostPort.orEmpty(),
                    sourceLabel = "Manual payload parsed",
                    resultMessage = "Payload parsed successfully",
                    resultTone = StatusTone.HEALTHY
                ),
                errorMessage = null
            )
        }
        scheduleClientSessionPersist()
    }

    private fun startPairingRequest() {
        viewModelScope.launch(dispatchers.io) {
            val current = state.value
            if (current.mode != ZCamMode.CLIENT) return@launch
            if (current.clientHost.isBlank()) {
                _state.update { it.copy(errorMessage = "Set server host before pairing.") }
                return@launch
            }

            val pairing = current.pairing
            val deviceId = pairing.deviceId.ifBlank { defaultDeviceId() }
            val displayName = pairing.displayName.ifBlank { "Android Client" }

            _state.update {
                it.copy(
                    pairing = it.pairing.copy(
                        loading = true,
                        resultMessage = "",
                        resultTone = StatusTone.NEUTRAL
                    ),
                    errorMessage = null
                )
            }

            when (
                val result = localClient.requestPairing(
                    target = currentTargetForCommands(),
                    deviceId = deviceId,
                    displayName = displayName,
                    clientType = PairingClientType.ANDROID_APP.name.lowercase()
                )
            ) {
                is ClientCallResult.Success -> {
                    _state.update {
                        it.copy(
                            pairing = it.pairing.copy(
                                loading = false,
                                requestId = result.value.requestId,
                                deviceId = result.value.deviceId,
                                displayName = result.value.displayName,
                                expiresAtEpochMs = result.value.expiresAtEpochMs,
                                verificationCodeInput = "",
                                resultMessage = "Pairing request sent. Enter the code shown on the server.",
                                resultTone = StatusTone.HEALTHY
                            ),
                            errorMessage = null
                        )
                    }
                    persistClientSession()
                }

                is ClientCallResult.Failure -> {
                    val failureMessage = pairingFailureMessage(result, prefix = "Pairing request failed")
                    _state.update {
                        it.copy(
                            pairing = it.pairing.copy(
                                loading = false,
                                resultMessage = failureMessage,
                                resultTone = StatusTone.ERROR
                            ),
                            errorMessage = failureMessage
                        )
                    }
                }
            }
        }
    }

    private fun submitPairing() {
        viewModelScope.launch(dispatchers.io) {
            val pairing = state.value.pairing
            if (pairing.requestId.isBlank() || pairing.verificationCodeInput.length != PAIRING_CODE_DIGITS) {
                _state.update { it.copy(errorMessage = "Start pairing first, then enter the ${PAIRING_CODE_DIGITS}-digit code from the server.") }
                return@launch
            }
            if (state.value.mode == ZCamMode.CLIENT && state.value.clientHost.isBlank()) {
                _state.update { it.copy(errorMessage = "Set server host before pairing.") }
                return@launch
            }

            _state.update {
                it.copy(pairing = it.pairing.copy(loading = true, resultMessage = "", resultTone = StatusTone.NEUTRAL))
            }
            when (
                val result = localClient.completePairingRequest(
                    target = currentTargetForCommands(),
                    requestId = pairing.requestId,
                    verificationCode = pairing.verificationCodeInput
                )
            ) {
                is ClientCallResult.Success -> {
                    _state.update { current ->
                        current.copy(
                            pairing = current.pairing.copy(
                                loading = false,
                                requestId = "",
                                verificationCodeInput = "",
                                issuedToken = result.value.tokenValue,
                                resultMessage = "Paired successfully (token: ${result.value.tokenId})",
                                resultTone = StatusTone.HEALTHY
                            ),
                            errorMessage = null
                        )
                    }
                    persistClientSession()
                }

                is ClientCallResult.Failure -> {
                    val failureMessage = pairingFailureMessage(result, prefix = "Pairing failed")
                    _state.update { current ->
                        current.copy(
                            pairing = current.pairing.copy(
                                loading = false,
                                resultMessage = failureMessage,
                                resultTone = StatusTone.ERROR
                            ),
                            errorMessage = failureMessage
                        )
                    }
                }
            }
        }
    }

    private fun cancelPendingPairing(requestId: String) {
        viewModelScope.launch(dispatchers.io) {
            if (requestId.isBlank()) return@launch
            runCatching {
                securityManager.cancelPairingRequest(requestId)
            }
        }
    }

    private fun recordingSourceLabel(target: ClientTarget): String = "${target.host}:${target.port}"

    private fun mapRecordingEvents(
        items: List<com.zcam.ui.RecordingItemUi>,
        events: List<com.zcam.client.ClientRecordingEvent>
    ): List<RecordingEventUi> {
        return events.map { event ->
            val matchedItem = event.recordingFileName
                ?.let { fileName -> items.firstOrNull { item -> item.fileName == fileName } }
                ?: matchRecordingItemForEvent(items, event.epochMs)
            val startedAtEpochMs = matchedItem?.startedAtEpochMs ?: event.recordingStartedAtEpochMs
            val endedAtEpochMs = matchedItem?.endedAtEpochMs ?: event.recordingEndedAtEpochMs
            val durationMs = if (startedAtEpochMs != null && endedAtEpochMs != null) {
                (endedAtEpochMs - startedAtEpochMs).coerceAtLeast(0L)
            } else {
                null
            }
            val offsetMs = (
                matchedItem?.let { item -> event.epochMs - item.startedAtEpochMs }
                    ?: event.recordingOffsetMs
                    ?: startedAtEpochMs?.let { start -> event.epochMs - start }
                )?.coerceAtLeast(0L)
            RecordingEventUi(
                epochMs = event.epochMs,
                confidencePercent = event.confidencePercent,
                source = event.source,
                recordingFileName = matchedItem?.fileName ?: event.recordingFileName,
                recordingStartedAtEpochMs = startedAtEpochMs,
                recordingEndedAtEpochMs = endedAtEpochMs,
                recordingOffsetMs = if (durationMs != null && offsetMs != null) {
                    offsetMs.coerceAtMost(durationMs)
                } else {
                    offsetMs
                }
            )
        }.sortedBy(RecordingEventUi::epochMs)
    }

    private fun matchRecordingItemForEvent(
        items: List<com.zcam.ui.RecordingItemUi>,
        eventEpochMs: Long
    ): com.zcam.ui.RecordingItemUi? {
        val directMatch = items.firstOrNull { item ->
            eventEpochMs in item.startedAtEpochMs..item.endedAtEpochMs
        }
        if (directMatch != null) return directMatch

        return items.minByOrNull { item ->
            when {
                eventEpochMs < item.startedAtEpochMs -> item.startedAtEpochMs - eventEpochMs
                eventEpochMs > item.endedAtEpochMs -> eventEpochMs - item.endedAtEpochMs
                else -> 0L
            }
        }?.takeIf { item ->
            val distance = when {
                eventEpochMs < item.startedAtEpochMs -> item.startedAtEpochMs - eventEpochMs
                eventEpochMs > item.endedAtEpochMs -> eventEpochMs - item.endedAtEpochMs
                else -> 0L
            }
            distance <= RECORDING_EVENT_MATCH_GRACE_MS
        }
    }

    private fun playbackCacheFile(fileName: String): File {
        return File(appContext.cacheDir, "zcam/playback/$fileName")
    }

    private fun cachedPlaybackUrlFor(item: com.zcam.ui.RecordingItemUi): String? {
        val file = playbackCacheFile(item.fileName)
        if (!file.exists() || !file.isFile) return null
        if (item.sizeBytes > 0L && file.length() != item.sizeBytes) {
            file.delete()
            return null
        }
        return Uri.fromFile(file).toString()
    }

    private fun createRecordingDownloadDestination(item: com.zcam.ui.RecordingItemUi): RecordingDownloadDestination? {
        val displayName = buildRecordingDownloadDisplayName(item.startedAtEpochMs)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createMediaStoreRecordingDownload(displayName)
        } else {
            createLocalRecordingDownload(displayName)
        }
    }

    private fun createMediaStoreRecordingDownload(displayName: String): RecordingDownloadDestination? {
        val resolver = appContext.contentResolver
        val uniqueName = nextAvailableMediaStoreRecordingName(displayName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, RECORDINGS_DOWNLOAD_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return MediaStoreRecordingDownloadDestination(
            resolver = resolver,
            uri = uri,
            displayName = uniqueName
        )
    }

    private fun nextAvailableMediaStoreRecordingName(displayName: String): String {
        val resolver = appContext.contentResolver
        val stem = displayName.removeSuffix(".mp4")
        var candidate = displayName
        var suffix = 2
        while (true) {
            val exists = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(candidate, RECORDINGS_DOWNLOAD_RELATIVE_PATH),
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
            if (!exists) return candidate
            candidate = "${stem}_$suffix.mp4"
            suffix += 1
        }
    }

    private fun createLocalRecordingDownload(displayName: String): RecordingDownloadDestination? {
        val baseDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(appContext.filesDir, "downloads")
        val outputDir = File(baseDir, "ZCam").apply { mkdirs() }
        val stem = displayName.removeSuffix(".mp4")
        var candidate = File(outputDir, displayName)
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(outputDir, "${stem}_$suffix.mp4")
            suffix += 1
        }
        return LocalFileRecordingDownloadDestination(candidate)
    }

    private fun buildRecordingDownloadDisplayName(startedAtEpochMs: Long): String {
        val stamp = java.time.Instant.ofEpochMilli(startedAtEpochMs)
            .atZone(ZoneId.systemDefault())
            .format(RECORDING_DOWNLOAD_FILE_FORMATTER)
        return "ZCam_${stamp}.mp4"
    }

    private fun copyLocalFileWithProgress(
        source: File,
        destination: java.io.OutputStream,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit
    ) {
        source.inputStream().use { input ->
            val totalBytes = source.length().takeIf { it > 0L }
            val buffer = ByteArray(64 * 1024)
            var copiedBytes = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                destination.write(buffer, 0, read)
                copiedBytes += read
                onProgress(copiedBytes, totalBytes)
            }
            destination.flush()
        }
    }

    private fun pairingFailureMessage(
        result: ClientCallResult.Failure,
        prefix: String
    ): String {
        val payload = result.responseBody?.let { body ->
            runCatching { JSONObject(body) }.getOrNull()
        }
        val explicitMessage = payload?.optString("message").orEmpty().ifBlank { null }
        if (explicitMessage != null) {
            return explicitMessage
        }
        if (result.reason == "pairing_locked") {
            val retryAfterSeconds = payload
                ?.takeIf { it.has("retryAfterSeconds") && !it.isNull("retryAfterSeconds") }
                ?.optInt("retryAfterSeconds")
                ?.takeIf { it > 0 }
                ?: DEFAULT_PAIRING_COOLDOWN_SECONDS
            return "Pairing locked. Try again in $retryAfterSeconds seconds."
        }
        return "$prefix: ${result.reason}"
    }

    private fun updateSettingsDraft(transform: SettingsUiState.() -> SettingsUiState) {
        _state.update { current ->
            current.copy(
                settings = current.settings.transform().copy(
                    resultMessage = "",
                    resultTone = StatusTone.NEUTRAL
                ),
                errorMessage = null
            )
        }
    }

    private fun updateSettingsFeatureFlag(flag: FeatureFlag, enabled: Boolean) {
        updateSettingsDraft {
            when (flag) {
                FeatureFlag.MJPEG_STREAMING -> copy(mjpegStreamingEnabled = enabled)
                FeatureFlag.LOOP_RECORDING -> copy(loopRecordingEnabled = enabled)
                FeatureFlag.AUDIO_PUSH_TO_TALK -> copy(audioPushToTalkEnabled = enabled)
                FeatureFlag.AUDIO_LIVE -> copy(audioLiveEnabled = enabled)
                FeatureFlag.AUDIO_PLAYBACK -> copy(audioPlaybackEnabled = enabled)
                FeatureFlag.TRUSTED_DEVICES -> copy(trustedDevicesEnabled = enabled)
                FeatureFlag.WATCHDOG_RECOVERY -> copy(watchdogRecoveryEnabled = enabled)
            }
        }
    }

    private fun saveSettings() {
        viewModelScope.launch(dispatchers.io) {
            val current = state.value
            val draft = current.settings
            val detectedCatalog = detectLocalRearLensCatalog()
            localRearLensCatalog = detectedCatalog
            val serverPort = draft.serverPortInput.toIntOrNull()
            val streamWidth = draft.streamWidthInput.toIntOrNull()
            val streamHeight = draft.streamHeightInput.toIntOrNull()
            val streamFps = draft.streamFpsInput.toIntOrNull()
            val previewWidth = draft.previewWidthInput.toIntOrNull()
            val previewHeight = draft.previewHeightInput.toIntOrNull()
            val previewFps = draft.previewFpsInput.toIntOrNull()
            val previewBitrate = draft.previewBitrateKbpsInput.toIntOrNull()
            val segmentMinutes = draft.segmentMinutesInput.toIntOrNull()
            val maxStorage = draft.maxStorageGbInput.toIntOrNull()
            val minFree = draft.minFreeStorageGbInput.toIntOrNull()

            val invalidFieldMessage = when {
                serverPort == null -> "Invalid server port"
                streamWidth == null -> "Invalid stream width"
                streamHeight == null -> "Invalid stream height"
                streamFps == null -> "Invalid stream FPS"
                previewWidth == null -> "Invalid preview width"
                previewHeight == null -> "Invalid preview height"
                previewFps == null -> "Invalid preview FPS"
                previewBitrate == null -> "Invalid preview bitrate"
                segmentMinutes == null -> "Invalid segment duration"
                maxStorage == null -> "Invalid max storage value"
                minFree == null -> "Invalid min free storage value"
                draft.pinInput.isBlank() -> "PIN is required"
                draft.apiTokenInput.isBlank() -> "API token is required"
                draft.rearLensSelection == RearCameraLens.ULTRA_WIDE && !detectedCatalog.ultraWideAvailable ->
                    "Ultra-wide camera not detected on this device"
                else -> null
            }

            if (invalidFieldMessage != null) {
                _state.update {
                    it.copy(
                        settings = it.settings.copy(
                            saving = false,
                            resultMessage = invalidFieldMessage,
                            resultTone = StatusTone.ERROR
                        ),
                        errorMessage = invalidFieldMessage
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    settings = it.settings.copy(
                        saving = true,
                        resultMessage = "",
                        resultTone = StatusTone.NEUTRAL
                    ),
                    errorMessage = null
                )
            }

            val candidate = settingsSnapshot.copy(
                serverPort = serverPort!!,
                stream = settingsSnapshot.stream.copy(
                    resolution = settingsSnapshot.stream.resolution.copy(
                        width = streamWidth!!,
                        height = streamHeight!!
                    ),
                    fps = streamFps!!,
                    rearLens = draft.rearLensSelection,
                    preview = settingsSnapshot.stream.preview.copy(
                        transport = draft.previewTransportSelection,
                        resolution = settingsSnapshot.stream.preview.resolution.copy(
                            width = previewWidth!!,
                            height = previewHeight!!
                        ),
                        fps = previewFps!!,
                        bitrateKbps = previewBitrate!!
                    )
                ),
                recording = settingsSnapshot.recording.copy(
                    segmentMinutes = segmentMinutes!!,
                    maxStorageGb = maxStorage!!,
                    minFreeStorageGb = minFree!!
                ),
                security = settingsSnapshot.security.copy(
                    pinCode = draft.pinInput,
                    apiToken = draft.apiTokenInput
                ),
                featureFlags = settingsSnapshot.featureFlags.copy(
                    mjpegStreaming = draft.mjpegStreamingEnabled,
                    loopRecording = draft.loopRecordingEnabled,
                    audioPushToTalk = draft.audioPushToTalkEnabled,
                    audioLive = draft.audioLiveEnabled,
                    audioPlayback = draft.audioPlaybackEnabled,
                    trustedDevices = draft.trustedDevicesEnabled,
                    watchdogRecovery = draft.watchdogRecoveryEnabled
                )
            )

            when (val result = runtimeSettingsRepository.updateSettings(candidate)) {
                is RuntimeSettingsUpdateResult.Success -> {
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(
                                saving = false,
                                resultMessage = "Settings saved",
                                resultTone = StatusTone.HEALTHY
                            ),
                            errorMessage = null
                        )
                    }
                }
                is RuntimeSettingsUpdateResult.ValidationFailed -> {
                    val reason = result.errors.joinToString(separator = "; ")
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(
                                saving = false,
                                resultMessage = "Validation failed: $reason",
                                resultTone = StatusTone.ERROR
                            ),
                            errorMessage = "Validation failed: $reason"
                        )
                    }
                }
                is RuntimeSettingsUpdateResult.Forbidden -> {
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(
                                saving = false,
                                resultMessage = "Forbidden: ${result.reason}",
                                resultTone = StatusTone.ERROR
                            ),
                            errorMessage = "Forbidden: ${result.reason}"
                        )
                    }
                }
            }
        }
    }

    private fun revokeTrustedDevice(deviceId: String) {
        viewModelScope.launch(dispatchers.io) {
            if (deviceId.isBlank()) return@launch
            when (val result = runtimeSettingsRepository.removeTrustedDevice(deviceId)) {
                is RuntimeSettingsUpdateResult.Success -> {
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(
                                resultMessage = "Device revoked: $deviceId",
                                resultTone = StatusTone.HEALTHY
                            ),
                            errorMessage = null
                        )
                    }
                }
                is RuntimeSettingsUpdateResult.ValidationFailed -> {
                    val reason = result.errors.joinToString(separator = "; ")
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(
                                resultMessage = "Revoke failed: $reason",
                                resultTone = StatusTone.ERROR
                            ),
                            errorMessage = "Revoke failed: $reason"
                        )
                    }
                }
                is RuntimeSettingsUpdateResult.Forbidden -> {
                    _state.update {
                        it.copy(
                            settings = it.settings.copy(
                                resultMessage = "Revoke forbidden: ${result.reason}",
                                resultTone = StatusTone.ERROR
                            ),
                            errorMessage = "Revoke forbidden: ${result.reason}"
                        )
                    }
                }
            }
        }
    }

    private fun scheduleClientSessionPersist() {
        clientSessionSyncJob?.cancel()
        clientSessionSyncJob = viewModelScope.launch(dispatchers.io) {
            delay(CLIENT_SESSION_SYNC_DEBOUNCE_MS)
            persistClientSession()
        }
    }

    private suspend fun persistClientSession() {
        val current = state.value
        val parsedPort = current.clientPort.toIntOrNull() ?: settingsSnapshot.serverPort
        val existingSession = clientSessionRepository.session.first()
        val now = System.currentTimeMillis()
        val pairedAtEpochMs = when {
            current.pairing.issuedToken.isBlank() -> 0L
            current.pairing.issuedToken == existingSession.issuedToken && existingSession.pairedAtEpochMs > 0L -> {
                existingSession.pairedAtEpochMs
            }
            else -> now
        }
        clientSessionRepository.saveSession(
            ClientSession(
                serverHost = current.clientHost.trim(),
                serverPort = parsedPort.coerceIn(1, 65535),
                deviceId = current.pairing.deviceId.ifBlank { defaultDeviceId() },
                displayName = current.pairing.displayName.ifBlank { "Android Client" },
                issuedToken = current.pairing.issuedToken,
                pairedAtEpochMs = pairedAtEpochMs,
                lastUpdatedAtEpochMs = now,
                lastModeName = current.mode.name
            )
        )
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

    override fun onCleared() {
        playbackLoadJob?.cancel()
        recordingDownloadJob?.cancel()
        viewModelScope.launch(dispatchers.io) {
            localAudioTransport.stopAll()
        }
        super.onCleared()
    }

    private fun ensureRecordingsDefaults() {
        val current = state.value.recordings
        if (current.fromInput.isNotBlank() || current.toInput.isNotBlank()) return
        val now = System.currentTimeMillis()
        val from = now - DEFAULT_RECORDINGS_LOOKBACK_MS
        _state.update {
            it.copy(
                recordings = it.recordings.copy(
                    fromInput = formatRecordingTimeInput(from),
                    toInput = formatRecordingTimeInput(now)
                )
            )
        }
    }

    private fun formatRecordingTimeInput(epochMs: Long): String {
        return java.time.Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(RECORDINGS_DATE_TIME_FORMATTER)
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

    private fun RuntimeSettings.toUiSettings(
        previous: SettingsUiState,
        ultraWideLensAvailable: Boolean
    ): SettingsUiState {
        return previous.copy(
            serverPortInput = serverPort.toString(),
            streamWidthInput = stream.resolution.width.toString(),
            streamHeightInput = stream.resolution.height.toString(),
            streamFpsInput = stream.fps.toString(),
            streamCodecLabel = stream.codec.name,
            rearLensSelection = stream.rearLens,
            ultraWideLensAvailable = ultraWideLensAvailable,
            previewTransportSelection = stream.preview.transport,
            previewProfileSelection = PreviewProfile.match(stream.preview),
            previewWidthInput = stream.preview.resolution.width.toString(),
            previewHeightInput = stream.preview.resolution.height.toString(),
            previewFpsInput = stream.preview.fps.toString(),
            previewBitrateKbpsInput = stream.preview.bitrateKbps.toString(),
            segmentMinutesInput = recording.segmentMinutes.toString(),
            maxStorageGbInput = recording.maxStorageGb.toString(),
            minFreeStorageGbInput = recording.minFreeStorageGb.toString(),
            pinInput = security.pinCode,
            apiTokenInput = security.apiToken,
            mjpegStreamingEnabled = featureFlags.mjpegStreaming,
            loopRecordingEnabled = featureFlags.loopRecording,
            audioPushToTalkEnabled = featureFlags.audioPushToTalk,
            audioLiveEnabled = featureFlags.audioLive,
            audioPlaybackEnabled = featureFlags.audioPlayback,
            trustedDevicesEnabled = featureFlags.trustedDevices,
            watchdogRecoveryEnabled = featureFlags.watchdogRecovery,
            trustedDevices = security.trustedDevices
                .sortedByDescending(TrustedDevice::addedAtEpochMillis)
                .map { device ->
                    TrustedDeviceUi(
                        deviceId = device.deviceId,
                        displayName = device.displayName,
                        addedAtEpochMillis = device.addedAtEpochMillis
                    )
                },
            saving = false
        )
    }

    private fun PendingPairingRequest.toUi(): PendingPairingRequestUi {
        return PendingPairingRequestUi(
            requestId = requestId,
            displayName = displayName,
            deviceId = deviceId,
            clientTypeLabel = when (clientType) {
                PairingClientType.ANDROID_APP -> "Android app"
                PairingClientType.WEB_BROWSER -> "Web browser"
            },
            verificationCode = verificationCode.chunked(3).joinToString(" "),
            expiresAtEpochMs = expiresAtEpochMs
        )
    }

    private fun refreshLocalRearLensCatalog() {
        viewModelScope.launch(dispatchers.io) {
            val catalog = detectLocalRearLensCatalog()
            localRearLensCatalog = catalog
            _state.update { current ->
                current.copy(
                    ultraWideAvailable = if (current.mode == ZCamMode.SERVER) {
                        catalog.ultraWideAvailable
                    } else {
                        current.ultraWideAvailable
                    },
                    settings = current.settings.copy(
                        ultraWideLensAvailable = if (current.mode == ZCamMode.SERVER) {
                            catalog.ultraWideAvailable
                        } else {
                            current.settings.ultraWideLensAvailable
                        }
                    )
                )
            }
        }
    }

    private suspend fun detectLocalRearLensCatalog(): RearCameraLensCatalog = withContext(dispatchers.io) {
        runCatching {
            RearCameraLensDetector.detectCatalog(appContext)
        }.getOrDefault(RearCameraLensCatalog())
    }

    private fun cameraLensUi(
        selectedLens: RearCameraLens,
        activeLens: RearCameraLens,
        runtimeActive: Boolean
    ): Pair<String, StatusTone> {
        return when {
            !runtimeActive -> "Lens: ${selectedLens.label} selected" to StatusTone.NEUTRAL
            activeLens != selectedLens -> "Lens: ${activeLens.label} (fallback active)" to StatusTone.WARNING
            else -> "Lens: ${activeLens.label}" to StatusTone.HEALTHY
        }
    }

    private fun previewTransportLabel(
        transport: PreviewTransport,
        usingFallback: Boolean
    ): String {
        return when {
            transport == PreviewTransport.H264 && !usingFallback -> "Preview transport: H.264"
            transport == PreviewTransport.MJPEG && usingFallback -> "Preview transport: MJPEG fallback"
            transport == PreviewTransport.MJPEG -> "Preview transport: MJPEG"
            else -> "Preview transport: H.264 fallback"
        }
    }

    private fun previewDiagnosticsLabel(
        width: Int,
        height: Int,
        targetFps: Int,
        targetBitrateKbps: Int,
        estimatedBitrateKbps: Int,
        sentFps: Int,
        error: String? = null
    ): String {
        val base = "${width}x$height ${targetFps} FPS target ${targetBitrateKbps} kbps"
        val live = if (estimatedBitrateKbps > 0 || sentFps > 0) {
            " | sent ${sentFps} FPS | est ${estimatedBitrateKbps} kbps"
        } else {
            ""
        }
        val errorText = error?.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        return base + live + errorText
    }

    private fun refreshServerLanHost() {
        val lanHost = findLocalLanHost()
        _state.update { current ->
            current.copy(serverLanHost = lanHost ?: "")
        }
    }

    private fun preferredPairingHostPort(): String {
        val current = state.value
        return if (current.mode == ZCamMode.CLIENT) {
            if (current.clientHost.isBlank()) {
                ""
            } else {
                "${current.clientHost}:${current.clientPort.ifBlank { settingsSnapshot.serverPort.toString() }}"
            }
        } else {
            val host = current.serverLanHost.ifBlank { findLocalLanHost().orEmpty() }
            if (host.isBlank()) "" else "$host:${settingsSnapshot.serverPort}"
        }
    }

    private fun normalizePairingPayloadHost(payload: String, preferredHostPort: String): String {
        val parsed = parsePairingPayload(payload) ?: return payload
        val currentHost = parsed.hostPort.orEmpty()
        val nextHost = if (currentHost.isBlank() || isLoopbackHostPort(currentHost)) {
            preferredHostPort.ifBlank { currentHost }
        } else {
            currentHost
        }

        return buildString {
            append("zcam://pair")
            var hasQuery = false
            if (!parsed.sessionId.isNullOrBlank()) {
                append(if (hasQuery) '&' else '?')
                append("sid=").append(Uri.encode(parsed.sessionId))
                hasQuery = true
            }
            if (!parsed.pairingCode.isNullOrBlank()) {
                append(if (hasQuery) '&' else '?')
                append("code=").append(Uri.encode(parsed.pairingCode))
                hasQuery = true
            }
            if (nextHost.isNotBlank()) {
                append(if (hasQuery) '&' else '?')
                append("host=").append(Uri.encode(nextHost))
            }
        }
    }

    private fun parsePairingPayload(payload: String): ParsedPairingPayload? {
        return runCatching {
            val trimmed = payload.trim()
            if (trimmed.isBlank()) return null
            if (!trimmed.startsWith("zcam://", ignoreCase = true)) return null
            val payloadWithoutScheme = trimmed.substringAfter("://", "")
            if (!payloadWithoutScheme.startsWith("pair", ignoreCase = true)) return null

            val query = trimmed.substringAfter('?', "")
            if (query.isBlank()) return null
            val params = parseQueryParams(query)

            val sid = params["sid"] ?: params["sessionid"] ?: params["session_id"]
            val code = params["code"] ?: params["pairingcode"] ?: params["pairing_code"]
            val hostPortRaw = params["host"].orEmpty()
            val (host, port) = parseHostPort(hostPortRaw)
            if (sid.isNullOrBlank() && code.isNullOrBlank() && host.isNullOrBlank()) return null

            ParsedPairingPayload(
                sessionId = sid?.ifBlank { null },
                pairingCode = code?.ifBlank { null },
                host = host,
                port = port
            )
        }.getOrNull()
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        return query.split('&')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decodeComponent(part.substring(0, separator)).trim().lowercase()
                val value = decodeComponent(part.substring(separator + 1)).trim()
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun decodeComponent(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun parseRecordingTimeInput(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        value.toLongOrNull()?.let { epoch ->
            return if (epoch >= 0L) epoch else INVALID_TIME_INPUT
        }
        val parsedLocal = runCatching {
            LocalDateTime.parse(value, RECORDINGS_DATE_TIME_FORMATTER)
        }.getOrNull() ?: return INVALID_TIME_INPUT
        return parsedLocal.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun parseHostPort(hostPort: String): Pair<String?, Int?> {
        val trimmed = hostPort.trim()
        if (trimmed.isBlank()) return null to null

        if (trimmed.startsWith("[") && trimmed.contains("]")) {
            val endBracket = trimmed.indexOf(']')
            val host = trimmed.substring(1, endBracket)
            val portPart = trimmed.substring(endBracket + 1).removePrefix(":")
            val port = portPart.toIntOrNull()?.takeIf { it in 1..65535 }
            return host.ifBlank { null } to port
        }

        val host = trimmed.substringBeforeLast(':')
        val portPart = trimmed.substringAfterLast(':', missingDelimiterValue = "")
        val parsedPort = portPart.toIntOrNull()?.takeIf { it in 1..65535 }
        return if (parsedPort != null && host.isNotBlank()) {
            host to parsedPort
        } else {
            trimmed to null
        }
    }

    private fun isLoopbackHostPort(hostPort: String): Boolean {
        val host = hostPort.substringBefore(':').trim().lowercase()
        return isLoopbackHost(host)
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        return normalized == "127.0.0.1" ||
            normalized == "localhost" ||
            normalized == "0.0.0.0" ||
            normalized == "::1"
    }

    private fun findLocalLanHost(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        return@runCatching address.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun defaultDeviceId(): String {
        val model = Build.MODEL?.trim().orEmpty().replace(Regex("[^A-Za-z0-9._:-]"), "_")
        return if (model.isBlank()) "android-client" else "android-$model"
    }

    private fun shouldRefreshPreviewSnapshots(current: ZCamUiState): Boolean {
        return !current.showModePicker &&
            current.screen == ZCamScreen.MAIN &&
            current.previewMjpegFallbackUrl.isNotBlank() &&
            current.previewTransport == PreviewTransport.MJPEG
    }

    private fun powerUiState(
        batteryPercent: Int?,
        charging: Boolean?,
        remote: Boolean
    ): PowerUiState {
        val prefix = if (remote) "Server battery" else "Battery"
        val label = when {
            batteryPercent != null && charging == true -> "$prefix: $batteryPercent% - charging"
            batteryPercent != null -> "$prefix: $batteryPercent%"
            charging == true -> "$prefix: charging"
            else -> "$prefix unavailable"
        }
        val tone = when {
            batteryPercent == null && charging == null -> StatusTone.WARNING
            charging == true -> StatusTone.HEALTHY
            batteryPercent == null -> StatusTone.WARNING
            batteryPercent < 15 -> StatusTone.ERROR
            batteryPercent < 35 -> StatusTone.WARNING
            else -> StatusTone.HEALTHY
        }
        return PowerUiState(label = label, tone = tone)
    }

    private fun String.toStoredModeOrNull(): ZCamMode? {
        return runCatching { ZCamMode.valueOf(trim().uppercase()) }.getOrNull()
    }

    private data class ParsedPairingPayload(
        val sessionId: String?,
        val pairingCode: String?,
        val host: String?,
        val port: Int?
    ) {
        val hostPort: String?
            get() = when {
                host.isNullOrBlank() -> null
                port == null -> host
                else -> "$host:$port"
            }
    }

    private data class PowerUiState(
        val label: String,
        val tone: StatusTone
    )

    private sealed interface RecordingDownloadDestination {
        val displayName: String
        fun openOutputStream(): java.io.OutputStream?
        fun commit()
        fun abort()
    }

    private class MediaStoreRecordingDownloadDestination(
        private val resolver: android.content.ContentResolver,
        private val uri: Uri,
        override val displayName: String
    ) : RecordingDownloadDestination {
        override fun openOutputStream(): java.io.OutputStream? {
            return resolver.openOutputStream(uri, "w")
        }

        override fun commit() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, values, null, null)
            }
        }

        override fun abort() {
            resolver.delete(uri, null, null)
        }
    }

    private class LocalFileRecordingDownloadDestination(
        private val file: File
    ) : RecordingDownloadDestination {
        override val displayName: String
            get() = file.name

        override fun openOutputStream(): java.io.OutputStream? {
            file.parentFile?.mkdirs()
            return file.outputStream()
        }

        override fun commit() = Unit

        override fun abort() {
            file.delete()
        }
    }

    private companion object {
        const val CLIENT_STATUS_REFRESH_MS = 2_000L
        const val CLIENT_SESSION_SYNC_DEBOUNCE_MS = 250L
        const val THERMAL_REFRESH_MS = 2_500L
        const val POWER_REFRESH_MS = 10_000L
        const val PREVIEW_SNAPSHOT_REFRESH_MS = 1_500L
        const val PREVIEW_STALE_FRAME_MS = 5_000L
        const val VOLUME_DEBOUNCE_MS = 180L
        const val PAIRING_CODE_DIGITS = 6
        const val DEFAULT_RECORDINGS_LOOKBACK_MS = 12 * 60 * 60 * 1000L
        const val DEFAULT_PAIRING_COOLDOWN_SECONDS = 30
        const val RECORDING_EVENT_MATCH_GRACE_MS = 2_000L
        const val RECORDINGS_DOWNLOAD_RELATIVE_PATH = "Download/ZCam/"
        val RECORDINGS_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val RECORDING_DOWNLOAD_FILE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.US)
        const val INVALID_TIME_INPUT = Long.MIN_VALUE
    }
}
