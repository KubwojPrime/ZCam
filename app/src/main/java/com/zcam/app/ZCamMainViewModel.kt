package com.zcam.app

import android.content.Context
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
import com.zcam.ui.StatusTone
import com.zcam.ui.ZCamMode
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
import java.net.Inet4Address
import java.net.NetworkInterface
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
                _state.update { current ->
                    current.copy(
                        mode = action.mode,
                        errorMessage = null
                    )
                }
                refreshServerLanHost()
            }

            ZCamUiAction.RequestPermissions -> Unit
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
            is ZCamUiAction.PairingPayloadChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(payloadInput = action.value))
            }

            ZCamUiAction.ApplyPairingPayload -> applyPairingPayloadFromInput()
            is ZCamUiAction.PairingSessionIdChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(sessionId = action.value.take(128)))
            }

            is ZCamUiAction.PairingCodeChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(pairingCode = action.value.take(128)))
            }

            is ZCamUiAction.PairingPinChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(pin = action.value.filter(Char::isDigit).take(10)))
            }

            is ZCamUiAction.PairingDeviceIdChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(deviceId = action.value.take(64)))
            }

            is ZCamUiAction.PairingDisplayNameChanged -> _state.update {
                it.copy(pairing = it.pairing.copy(displayName = action.value.take(64)))
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
                        clientPort = if (state.mode == ZCamMode.SERVER || state.clientPort.isBlank()) {
                            nextPort
                        } else {
                            state.clientPort
                        }
                    )
                }
                refreshServerLanHost()
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
                            previewLabel = "Updated ${System.currentTimeMillis()}"
                        )

                        is ClientCallResult.Failure -> current.copy(
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
            _state.update { current ->
                when (result) {
                    is ClientCallResult.Success -> {
                        val status = result.value
                        current.copy(
                            clientReachable = status.alive,
                            clientStatusLabel = "alive=${status.alive} clients=${status.streamClients} video=${status.videoRunning}"
                        )
                    }

                    is ClientCallResult.Failure -> current.copy(
                        clientReachable = false,
                        clientStatusLabel = "unreachable (${result.reason})"
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
            _state.update { current ->
                when (result) {
                    is ClientCallResult.Success -> current.copy(pttPressed = pressed, errorMessage = null)
                    is ClientCallResult.Failure -> current.copy(errorMessage = "Push-to-talk failed: ${result.reason}")
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
                    it.copy(errorMessage = "Volume update failed: ${result.reason}")
                }
            }
        }
    }

    private fun requestPairingQr() {
        viewModelScope.launch(dispatchers.io) {
            _state.update {
                it.copy(
                    pairing = it.pairing.copy(
                        loading = true,
                        resultMessage = "",
                        resultTone = StatusTone.NEUTRAL
                    )
                )
            }

            when (val result = localClient.fetchPairingQr(currentTargetForCommands())) {
                is ClientCallResult.Success -> {
                    val preferredHostPort = preferredPairingHostPort()
                    val normalizedPayload = normalizePairingPayloadHost(result.value.qrPayload, preferredHostPort)
                    val parsed = parsePairingPayload(normalizedPayload)

                    _state.update { current ->
                        val nextHost = parsed?.host ?: current.clientHost
                        val nextPort = parsed?.port?.toString() ?: current.clientPort
                        current.copy(
                            clientHost = if (current.mode == ZCamMode.CLIENT && parsed?.host != null) nextHost else current.clientHost,
                            clientPort = if (current.mode == ZCamMode.CLIENT && parsed?.port != null) nextPort else current.clientPort,
                            pairing = current.pairing.copy(
                                loading = false,
                                sessionId = parsed?.sessionId ?: result.value.sessionId,
                                pairingCode = parsed?.pairingCode ?: result.value.pairingCode,
                                qrPayload = normalizedPayload,
                                payloadInput = normalizedPayload,
                                resolvedHostPort = parsed?.hostPort.orEmpty(),
                                sourceLabel = if (current.mode == ZCamMode.SERVER) {
                                    "Server pairing challenge ready"
                                } else {
                                    "Challenge fetched from server"
                                },
                                expiresAtEpochMs = result.value.expiresAtEpochMs,
                                resultMessage = "Pairing challenge ready",
                                resultTone = StatusTone.HEALTHY
                            )
                        )
                    }
                }

                is ClientCallResult.Failure -> _state.update { current ->
                    current.copy(
                        pairing = current.pairing.copy(
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

    private fun applyPairingPayloadFromInput() {
        val rawPayload = state.value.pairing.payloadInput.trim()
        if (rawPayload.isBlank()) {
            _state.update { it.copy(errorMessage = "Pairing payload is empty.") }
            return
        }

        val parsed = parsePairingPayload(rawPayload)
        if (parsed == null) {
            _state.update {
                it.copy(errorMessage = "Unable to parse pairing payload. Expected: zcam://pair?sid=...&code=...&host=...")
            }
            return
        }

        _state.update { current ->
            current.copy(
                clientHost = if (current.mode == ZCamMode.CLIENT && parsed.host != null) parsed.host else current.clientHost,
                clientPort = if (current.mode == ZCamMode.CLIENT && parsed.port != null) parsed.port.toString() else current.clientPort,
                pairing = current.pairing.copy(
                    sessionId = parsed.sessionId ?: current.pairing.sessionId,
                    pairingCode = parsed.pairingCode ?: current.pairing.pairingCode,
                    resolvedHostPort = parsed.hostPort.orEmpty(),
                    sourceLabel = "Manual payload parsed",
                    resultMessage = "Payload parsed successfully",
                    resultTone = StatusTone.HEALTHY
                ),
                errorMessage = null
            )
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
                is ClientCallResult.Success -> _state.update { current ->
                    current.copy(
                        pairing = current.pairing.copy(
                            loading = false,
                            issuedToken = result.value.tokenValue,
                            resultMessage = "Paired successfully (token: ${result.value.tokenId})",
                            resultTone = StatusTone.HEALTHY
                        ),
                        errorMessage = null
                    )
                }

                is ClientCallResult.Failure -> _state.update { current ->
                    current.copy(
                        pairing = current.pairing.copy(
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

    private fun refreshServerLanHost() {
        val lanHost = findLocalLanHost()
        _state.update { current ->
            current.copy(serverLanHost = lanHost ?: "")
        }
    }

    private fun preferredPairingHostPort(): String {
        val current = state.value
        return if (current.mode == ZCamMode.CLIENT) {
            "${current.clientHost.ifBlank { "127.0.0.1" }}:${current.clientPort.ifBlank { settingsSnapshot.serverPort.toString() }}"
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

        val sid = parsed.sessionId ?: return payload
        val code = parsed.pairingCode ?: return payload
        return buildString {
            append("zcam://pair?sid=").append(Uri.encode(sid))
            append("&code=").append(Uri.encode(code))
            if (nextHost.isNotBlank()) {
                append("&host=").append(Uri.encode(nextHost))
            }
        }
    }

    private fun parsePairingPayload(payload: String): ParsedPairingPayload? {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) return null
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("zcam", ignoreCase = true)) return null
        if (!uri.host.equals("pair", ignoreCase = true)) return null

        val sid = uri.getQueryParameter("sid") ?: uri.getQueryParameter("sessionId")
        val code = uri.getQueryParameter("code") ?: uri.getQueryParameter("pairingCode")
        val hostPortRaw = uri.getQueryParameter("host").orEmpty()
        val (host, port) = parseHostPort(hostPortRaw)

        return ParsedPairingPayload(
            sessionId = sid?.ifBlank { null },
            pairingCode = code?.ifBlank { null },
            host = host,
            port = port
        )
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
        return host == "127.0.0.1" || host == "localhost" || host == "0.0.0.0"
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
        const val THERMAL_REFRESH_MS = 2_500L
        const val VOLUME_DEBOUNCE_MS = 180L
    }
}
