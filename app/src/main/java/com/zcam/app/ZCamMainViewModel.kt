package com.zcam.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zcam.client.ClientCallResult
import com.zcam.client.ClientTarget
import com.zcam.client.LocalClient
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.FeatureFlag
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class ZCamMainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val runtimeHealthRepository: RuntimeHealthRepository,
    private val runtimeSettingsRepository: RuntimeSettingsRepository,
    private val runtimeStateRepository: RuntimeStateRepository,
    private val clientSessionRepository: ClientSessionRepository,
    private val localClient: LocalClient,
    private val securityManager: SecurityManager,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : ViewModel() {

    private val _state = MutableStateFlow(ZCamUiState())
    val state = _state.asStateFlow()

    private var settingsSnapshot: RuntimeSettings = RuntimeSettingsDefaults.value
    private var volumeSyncJob: Job? = null
    private var clientSessionSyncJob: Job? = null

    init {
        observeSettings()
        observeClientSession()
        observePendingPairingRequests()
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
                _state.update { current ->
                    current.copy(
                        mode = action.mode,
                        showModePicker = false,
                        screen = if (current.showModePicker) ZCamScreen.MAIN else current.screen,
                        showPairingSuggestionDialog = false,
                        errorMessage = null
                    )
                }
                refreshServerLanHost()
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
            }
            is ZCamUiAction.ClientPortChanged -> {
                _state.update { it.copy(clientPort = action.port.filter(Char::isDigit)) }
                scheduleClientSessionPersist()
            }

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
            is ZCamUiAction.PlayRecording -> playRecording(action.fileName)

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
            applyPairingPayloadFromInput()
        }
    }

    private fun observeSettings() {
        viewModelScope.launch(dispatchers.io) {
            runtimeSettingsRepository.settings.collectLatest { settings ->
                settingsSnapshot = settings
                _state.update { state ->
                    val nextPort = settings.serverPort.toString()
                    state.copy(
                        clientPort = if (state.mode == ZCamMode.SERVER || state.clientPort.isBlank()) {
                            nextPort
                        } else {
                            state.clientPort
                        },
                        settings = settings.toUiSettings(previous = state.settings)
                    )
                }
                refreshServerLanHost()
            }
        }
    }

    private fun observeClientSession() {
        viewModelScope.launch(dispatchers.io) {
            clientSessionRepository.session.collectLatest { session ->
                if (session == ClientSession()) return@collectLatest
                _state.update { current ->
                    current.copy(
                        clientHost = if (session.serverHost.isNotBlank()) session.serverHost else current.clientHost,
                        clientPort = if (session.serverPort in 1..65535) session.serverPort.toString() else current.clientPort,
                        pairing = current.pairing.copy(
                            deviceId = session.deviceId.ifBlank { current.pairing.deviceId },
                            displayName = session.displayName.ifBlank { current.pairing.displayName },
                            issuedToken = session.issuedToken.ifBlank { current.pairing.issuedToken }
                        )
                    )
                }
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
            }
        }
    }

    private fun refreshPreviewLoop() {
        viewModelScope.launch(dispatchers.io) {
            while (isActive) {
                val target = currentTargetForCommands()
                val preview = localClient.fetchSnapshot(target)
                _state.update { current ->
                    when (preview) {
                        is ClientCallResult.Success -> current.copy(
                            previewFrameJpeg = preview.value,
                            previewLabel = ""
                        )

                        is ClientCallResult.Failure -> current.copy(
                            previewLabel = "Preview unavailable"
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
            _state.update { current ->
                when (result) {
                    is ClientCallResult.Success -> {
                        val status = result.value
                        val syncVolumeFromServer = volumeSyncJob?.isActive != true
                        val serverVolume = status.audioVolumePercent
                            ?.coerceIn(
                                status.audioMinVolumePercent ?: 0,
                                status.audioMaxVolumePercent ?: 85
                            )
                        current.copy(
                            clientReachable = status.alive,
                            clientStatusLabel = if (status.alive) "Connected" else "Unavailable",
                            pttPressed = status.audioTransmitting,
                            liveListenEnabled = status.audioLiveListening,
                            volumePercent = if (syncVolumeFromServer && serverVolume != null) {
                                serverVolume
                            } else {
                                current.volumePercent
                            }
                        )
                    }

                    is ClientCallResult.Failure -> current.copy(
                        clientReachable = false,
                        clientStatusLabel = "Unavailable"
                    )
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
            val result = localClient.setPushToTalk(currentTargetForCommands(), enabled = pressed)
            _state.update { current ->
                when (result) {
                    is ClientCallResult.Success -> current.copy(pttPressed = pressed, errorMessage = null)
                    is ClientCallResult.Failure -> current.copy(
                        errorMessage = "Push-to-talk failed: ${result.reason}${result.responseBody?.let { " ($it)" } ?: ""}"
                    )
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
                    is ClientCallResult.Failure -> current.copy(
                        errorMessage = "Live listen failed: ${result.reason}${result.responseBody?.let { " ($it)" } ?: ""}"
                    )
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
                        resultMessage = "Loading recordings..."
                    ),
                    errorMessage = null
                )
            }

            when (
                val result = localClient.fetchRecordings(
                    target = currentTargetForCommands(),
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
                            sizeBytes = clip.sizeBytes
                        )
                    }
                    _state.update {
                        it.copy(
                            recordings = it.recordings.copy(
                                loading = false,
                                resultMessage = "Found ${items.size} recording(s)",
                                items = items
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
                                resultMessage = "Recordings request failed: ${result.reason}"
                            ),
                            errorMessage = "Recordings request failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                        )
                    }
                }
            }
        }
    }

    private fun playRecording(fileName: String) {
        viewModelScope.launch(dispatchers.io) {
            if (fileName.isBlank()) return@launch
            val url = localClient.buildRecordingPlaybackUrl(currentTargetForCommands(), fileName)
            val uri = Uri.parse(url)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                appContext.startActivity(intent)
                _state.update { it.copy(errorMessage = null) }
            }.onFailure { error ->
                _state.update {
                    it.copy(errorMessage = "Cannot open recording player: ${error.message}")
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

                is ClientCallResult.Failure -> _state.update {
                    it.copy(
                        pairing = it.pairing.copy(
                            loading = false,
                            resultMessage = "Pairing request failed: ${result.reason}",
                            resultTone = StatusTone.ERROR
                        ),
                        errorMessage = "Pairing request failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                    )
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

                is ClientCallResult.Failure -> _state.update { current ->
                    current.copy(
                        pairing = current.pairing.copy(
                            loading = false,
                            resultMessage = "Pairing failed: ${result.reason}",
                            resultTone = StatusTone.ERROR
                        ),
                        errorMessage = "Pairing failed: ${result.reason}${result.responseBody?.let { body -> " ($body)" } ?: ""}"
                    )
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
            val serverPort = draft.serverPortInput.toIntOrNull()
            val streamWidth = draft.streamWidthInput.toIntOrNull()
            val streamHeight = draft.streamHeightInput.toIntOrNull()
            val streamFps = draft.streamFpsInput.toIntOrNull()
            val segmentMinutes = draft.segmentMinutesInput.toIntOrNull()
            val maxStorage = draft.maxStorageGbInput.toIntOrNull()
            val minFree = draft.minFreeStorageGbInput.toIntOrNull()

            val invalidFieldMessage = when {
                serverPort == null -> "Invalid server port"
                streamWidth == null -> "Invalid stream width"
                streamHeight == null -> "Invalid stream height"
                streamFps == null -> "Invalid stream FPS"
                segmentMinutes == null -> "Invalid segment duration"
                maxStorage == null -> "Invalid max storage value"
                minFree == null -> "Invalid min free storage value"
                draft.pinInput.isBlank() -> "PIN is required"
                draft.apiTokenInput.isBlank() -> "API token is required"
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
                    fps = streamFps!!
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
        clientSessionRepository.saveSession(
            ClientSession(
                serverHost = current.clientHost.trim(),
                serverPort = parsedPort.coerceIn(1, 65535),
                deviceId = current.pairing.deviceId.ifBlank { defaultDeviceId() },
                displayName = current.pairing.displayName.ifBlank { "Android Client" },
                issuedToken = current.pairing.issuedToken,
                pairedAtEpochMs = if (current.pairing.issuedToken.isBlank()) 0L else System.currentTimeMillis(),
                lastUpdatedAtEpochMs = System.currentTimeMillis()
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

    private fun RuntimeSettings.toUiSettings(previous: SettingsUiState): SettingsUiState {
        return previous.copy(
            serverPortInput = serverPort.toString(),
            streamWidthInput = stream.resolution.width.toString(),
            streamHeightInput = stream.resolution.height.toString(),
            streamFpsInput = stream.fps.toString(),
            streamCodecLabel = stream.codec.name,
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

    private companion object {
        const val PREVIEW_REFRESH_MS = 1_200L
        const val CLIENT_STATUS_REFRESH_MS = 2_000L
        const val CLIENT_SESSION_SYNC_DEBOUNCE_MS = 250L
        const val THERMAL_REFRESH_MS = 2_500L
        const val VOLUME_DEBOUNCE_MS = 180L
        const val PAIRING_CODE_DIGITS = 6
        val RECORDINGS_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        const val INVALID_TIME_INPUT = Long.MIN_VALUE
    }
}
