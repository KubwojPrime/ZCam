package com.zcam.ui

import com.zcam.core.domain.config.FeatureFlag

enum class ZCamMode {
    SERVER,
    CLIENT
}

enum class ZCamScreen {
    MAIN,
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
    val sizeBytes: Long
)

data class RecordingsUiState(
    val fromInput: String = "",
    val toInput: String = "",
    val loading: Boolean = false,
    val resultMessage: String = "",
    val items: List<RecordingItemUi> = emptyList()
)

data class TrustedDeviceUi(
    val deviceId: String,
    val displayName: String,
    val addedAtEpochMillis: Long
)

data class SettingsUiState(
    val serverPortInput: String = "8080",
    val streamWidthInput: String = "1280",
    val streamHeightInput: String = "720",
    val streamFpsInput: String = "15",
    val streamCodecLabel: String = "H264",
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
    val sessionId: String = "",
    val pairingCode: String = "",
    val qrPayload: String = "",
    val payloadInput: String = "",
    val resolvedHostPort: String = "",
    val sourceLabel: String = "No pairing challenge loaded",
    val expiresAtEpochMs: Long = 0L,
    val pin: String = "",
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
    val runtimeOn: Boolean = false,
    val runtimeLabel: String = "Stopped",
    val runtimeTone: StatusTone = StatusTone.NEUTRAL,
    val previewFrameJpeg: ByteArray? = null,
    val previewLabel: String = "No frame",
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
    val serverLanHost: String = "",
    val pairing: PairingUiState = PairingUiState(
        pin = "0000",
        deviceId = "android-client",
        displayName = "Android Client"
    ),
    val recordings: RecordingsUiState = RecordingsUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val showPairingSuggestionDialog: Boolean = false,
    val errorMessage: String? = null,
    val working: Boolean = false
)

sealed interface ZCamUiAction {
    data class ScreenChanged(val screen: ZCamScreen) : ZCamUiAction
    data class ModeChanged(val mode: ZCamMode) : ZCamUiAction
    data object RequestPermissions : ZCamUiAction
    data object StartRuntime : ZCamUiAction
    data object StopRuntime : ZCamUiAction
    data object RefreshClientStatus : ZCamUiAction
    data class ClientHostChanged(val host: String) : ZCamUiAction
    data class ClientPortChanged(val port: String) : ZCamUiAction

    data class PushToTalkChanged(val pressed: Boolean) : ZCamUiAction
    data object ToggleLiveListen : ZCamUiAction
    data class PlayQuickSound(val clipId: String, val aversive: Boolean) : ZCamUiAction
    data class VolumeChanged(val levelPercent: Int) : ZCamUiAction
    data class RecordingsFromChanged(val value: String) : ZCamUiAction
    data class RecordingsToChanged(val value: String) : ZCamUiAction
    data object FetchRecordings : ZCamUiAction
    data class PlayRecording(val fileName: String) : ZCamUiAction

    data object RequestPairingQr : ZCamUiAction
    data class PairingPayloadChanged(val value: String) : ZCamUiAction
    data object ApplyPairingPayload : ZCamUiAction
    data class PairingSessionIdChanged(val value: String) : ZCamUiAction
    data class PairingCodeChanged(val value: String) : ZCamUiAction
    data class PairingPinChanged(val value: String) : ZCamUiAction
    data class PairingDeviceIdChanged(val value: String) : ZCamUiAction
    data class PairingDisplayNameChanged(val value: String) : ZCamUiAction
    data object SubmitPairing : ZCamUiAction

    data class SettingsServerPortChanged(val value: String) : ZCamUiAction
    data class SettingsStreamWidthChanged(val value: String) : ZCamUiAction
    data class SettingsStreamHeightChanged(val value: String) : ZCamUiAction
    data class SettingsStreamFpsChanged(val value: String) : ZCamUiAction
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
