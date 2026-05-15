package com.zcam.ui

import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.EventDetectionSensitivity
import com.zcam.core.domain.config.PreviewProfile
import com.zcam.core.domain.config.PreviewTransport
import com.zcam.core.domain.config.RearCameraLens

enum class ZCamMode {
    SERVER,
    CLIENT
}

enum class ZCamScreen {
    MAIN,
    RECORDINGS,
    PAIRING,
    SETTINGS
}

enum class StatusTone {
    NEUTRAL,
    HEALTHY,
    WARNING,
    ERROR
}

data class ComponentStatusUi(
    val label: String,
    val status: String,
    val details: String,
    val recoveryAttempts: Int,
    val tone: StatusTone
)

data class QuickSoundUi(
    val clipId: String,
    val label: String,
    val aversive: Boolean
)

data class RecordingItemUi(
    val fileName: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val container: String,
    val codec: String
)

data class RecordingEventUi(
    val epochMs: Long,
    val confidencePercent: Int,
    val source: String,
    val recordingFileName: String?,
    val recordingStartedAtEpochMs: Long?,
    val recordingEndedAtEpochMs: Long?,
    val recordingOffsetMs: Long?
)

data class RecordingsUiState(
    val fromInput: String = "",
    val toInput: String = "",
    val loading: Boolean = false,
    val resultMessage: String = "",
    val resultTone: StatusTone = StatusTone.NEUTRAL,
    val items: List<RecordingItemUi> = emptyList(),
    val events: List<RecordingEventUi> = emptyList(),
    val selectedFileName: String = "",
    val selectedPlaybackUrl: String = "",
    val selectedPlaybackOffsetMs: Long = 0L,
    val selectedPlaybackSourceLabel: String = "",
    val playbackLoading: Boolean = false,
    val playbackLoadingMessage: String = "",
    val playbackDownloadedBytes: Long = 0L,
    val playbackTotalBytes: Long? = null,
    val activeDownloadFileName: String = "",
    val downloadLoading: Boolean = false,
    val downloadMessage: String = "",
    val downloadDownloadedBytes: Long = 0L,
    val downloadTotalBytes: Long? = null
)

data class TrustedDeviceUi(
    val deviceId: String,
    val displayName: String,
    val addedAtEpochMillis: Long
)

data class PendingPairingRequestUi(
    val requestId: String,
    val displayName: String,
    val deviceId: String,
    val clientTypeLabel: String,
    val verificationCode: String,
    val expiresAtEpochMs: Long
)

data class SettingsUiState(
    val serverPortInput: String = "8080",
    val streamWidthInput: String = "1280",
    val streamHeightInput: String = "720",
    val streamFpsInput: String = "15",
    val streamCodecLabel: String = "H264",
    val rearLensSelection: RearCameraLens = RearCameraLens.MAIN,
    val ultraWideLensAvailable: Boolean = false,
    val eventSensitivitySelection: EventDetectionSensitivity = EventDetectionSensitivity.BALANCED,
    val previewTransportSelection: PreviewTransport = PreviewTransport.H264,
    val previewProfileSelection: PreviewProfile? = PreviewProfile.BALANCED,
    val previewWidthInput: String = "1280",
    val previewHeightInput: String = "720",
    val previewFpsInput: String = "15",
    val previewBitrateKbpsInput: String = "1200",
    val segmentMinutesInput: String = "5",
    val maxStorageGbInput: String = "32",
    val minFreeStorageGbInput: String = "5",
    val pinInput: String = "0000",
    val apiTokenInput: String = "local-token",
    val mjpegStreamingEnabled: Boolean = true,
    val loopRecordingEnabled: Boolean = true,
    val audioPushToTalkEnabled: Boolean = true,
    val audioLiveEnabled: Boolean = true,
    val audioPlaybackEnabled: Boolean = true,
    val trustedDevicesEnabled: Boolean = true,
    val watchdogRecoveryEnabled: Boolean = true,
    val trustedDevices: List<TrustedDeviceUi> = emptyList(),
    val saving: Boolean = false,
    val resultMessage: String = "",
    val resultTone: StatusTone = StatusTone.NEUTRAL
)

data class PairingUiState(
    val qrPayload: String = "",
    val payloadInput: String = "",
    val resolvedHostPort: String = "",
    val sourceLabel: String = "No server selected",
    val expiresAtEpochMs: Long = 0L,
    val requestId: String = "",
    val verificationCodeInput: String = "",
    val deviceId: String = "",
    val displayName: String = "",
    val loading: Boolean = false,
    val issuedToken: String = "",
    val resultMessage: String = "",
    val resultTone: StatusTone = StatusTone.NEUTRAL
)

data class ZCamUiState(
    val screen: ZCamScreen = ZCamScreen.MAIN,
    val mode: ZCamMode = ZCamMode.SERVER,
    val showModePicker: Boolean = true,
    val runtimeOn: Boolean = false,
    val runtimeLabel: String = "Stopped",
    val runtimeTone: StatusTone = StatusTone.NEUTRAL,
    val previewFrameJpeg: ByteArray? = null,
    val previewStreamUrl: String = "",
    val previewMjpegFallbackUrl: String = "",
    val previewTransport: PreviewTransport = PreviewTransport.H264,
    val previewLabel: String = "No frame",
    val previewStateLabel: String = "Preview idle",
    val previewStateTone: StatusTone = StatusTone.NEUTRAL,
    val previewTransportLabel: String = "Preview transport: H.264",
    val previewDiagnosticsLabel: String = "Preview profile: 720p 15 FPS 1200 kbps",
    val serverBatteryPercent: Int? = null,
    val serverCharging: Boolean? = null,
    val serverBatteryLabel: String = "Battery unavailable",
    val serverBatteryTone: StatusTone = StatusTone.NEUTRAL,
    val thermalLabel: String = "Unknown",
    val thermalTone: StatusTone = StatusTone.NEUTRAL,
    val recoveryLabel: String = "No active recovery",
    val recoveryTone: StatusTone = StatusTone.NEUTRAL,
    val permissionsReady: Boolean = true,
    val permissionsLabel: String = "Permissions ready",
    val componentStatuses: List<ComponentStatusUi> = emptyList(),
    val pttPressed: Boolean = false,
    val liveListenEnabled: Boolean = false,
    val volumePercent: Int = 40,
    val quickSounds: List<QuickSoundUi> = listOf(
        QuickSoundUi("alert_chime", "Alert", aversive = false),
        QuickSoundUi("door_knock", "Knock", aversive = false),
        QuickSoundUi("deterrent_1", "Deterrent", aversive = true)
    ),
    val clientHost: String = "192.168.1.10",
    val clientPort: String = "8080",
    val clientReachable: Boolean = false,
    val clientStatusLabel: String = "Client disconnected",
    val audioRuntimeLabel: String = "Audio idle",
    val audioRuntimeTone: StatusTone = StatusTone.NEUTRAL,
    val cameraLensLabel: String = "Lens: Main camera selected",
    val cameraLensTone: StatusTone = StatusTone.NEUTRAL,
    val eventSensitivityLabel: String = "Events: Balanced sensitivity",
    val ultraWideAvailable: Boolean = false,
    val clientTorchEnabled: Boolean = false,
    val clientNightModeEnabled: Boolean = false,
    val clientLowLightBoostSupported: Boolean = false,
    val clientZoomLinear: Float = 0f,
    val clientZoomRatio: Float = 1f,
    val clientMaxZoomRatio: Float = 1f,
    val serverLanHost: String = "",
    val pairing: PairingUiState = PairingUiState(
        deviceId = "android-client",
        displayName = "Android Client"
    ),
    val pendingPairingRequests: List<PendingPairingRequestUi> = emptyList(),
    val recordings: RecordingsUiState = RecordingsUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val showPairingSuggestionDialog: Boolean = false,
    val errorMessage: String? = null,
    val working: Boolean = false
)

sealed interface ZCamUiAction {
    data class ScreenChanged(val screen: ZCamScreen) : ZCamUiAction
    data class ModeChanged(val mode: ZCamMode) : ZCamUiAction
    data object OpenModePicker : ZCamUiAction
    data object RequestPermissions : ZCamUiAction
    data object StartRuntime : ZCamUiAction
    data object StopRuntime : ZCamUiAction
    data object RefreshClientStatus : ZCamUiAction
    data class ClientHostChanged(val host: String) : ZCamUiAction
    data class ClientPortChanged(val port: String) : ZCamUiAction

    data class SetTorchEnabled(val enabled: Boolean) : ZCamUiAction
    data class SetNightModeEnabled(val enabled: Boolean) : ZCamUiAction
    data class AdjustClientZoom(val deltaLinear: Float) : ZCamUiAction
    data object ResetClientZoom : ZCamUiAction
    data class PushToTalkChanged(val pressed: Boolean) : ZCamUiAction
    data object ToggleLiveListen : ZCamUiAction
    data class PlayQuickSound(val clipId: String, val aversive: Boolean) : ZCamUiAction
    data class VolumeChanged(val levelPercent: Int) : ZCamUiAction
    data class RecordingsFromChanged(val value: String) : ZCamUiAction
    data class RecordingsToChanged(val value: String) : ZCamUiAction
    data object FetchRecordings : ZCamUiAction
    data class PlayRecording(
        val fileName: String,
        val seekToEpochMs: Long? = null
    ) : ZCamUiAction
    data class PlayRecordingAtEpoch(val epochMs: Long) : ZCamUiAction
    data class DownloadRecording(val fileName: String) : ZCamUiAction

    data object RequestPairingQr : ZCamUiAction
    data class PairingPayloadChanged(val value: String) : ZCamUiAction
    data object ApplyPairingPayload : ZCamUiAction
    data class PairingDeviceIdChanged(val value: String) : ZCamUiAction
    data class PairingDisplayNameChanged(val value: String) : ZCamUiAction
    data class PairingVerificationCodeChanged(val value: String) : ZCamUiAction
    data object StartPairingRequest : ZCamUiAction
    data object SubmitPairing : ZCamUiAction
    data class CancelPendingPairing(val requestId: String) : ZCamUiAction

    data class SettingsServerPortChanged(val value: String) : ZCamUiAction
    data class SettingsStreamWidthChanged(val value: String) : ZCamUiAction
    data class SettingsStreamHeightChanged(val value: String) : ZCamUiAction
    data class SettingsStreamFpsChanged(val value: String) : ZCamUiAction
    data class SettingsRearLensChanged(val value: RearCameraLens) : ZCamUiAction
    data class SettingsEventSensitivityChanged(val value: EventDetectionSensitivity) : ZCamUiAction
    data class SettingsPreviewTransportChanged(val value: PreviewTransport) : ZCamUiAction
    data class SettingsPreviewProfileSelected(val value: PreviewProfile) : ZCamUiAction
    data class SettingsPreviewWidthChanged(val value: String) : ZCamUiAction
    data class SettingsPreviewHeightChanged(val value: String) : ZCamUiAction
    data class SettingsPreviewFpsChanged(val value: String) : ZCamUiAction
    data class SettingsPreviewBitrateChanged(val value: String) : ZCamUiAction
    data class SettingsSegmentMinutesChanged(val value: String) : ZCamUiAction
    data class SettingsMaxStorageGbChanged(val value: String) : ZCamUiAction
    data class SettingsMinFreeStorageGbChanged(val value: String) : ZCamUiAction
    data class SettingsPinChanged(val value: String) : ZCamUiAction
    data class SettingsApiTokenChanged(val value: String) : ZCamUiAction
    data class SettingsFlagChanged(val flag: FeatureFlag, val enabled: Boolean) : ZCamUiAction
    data class RevokeTrustedDevice(val deviceId: String) : ZCamUiAction
    data object SaveSettings : ZCamUiAction

    data object OpenPairingFromSuggestion : ZCamUiAction
    data object DismissPairingSuggestion : ZCamUiAction

    data object ClearError : ZCamUiAction
}
