package com.zcam.ui

enum class ZCamMode {
    SERVER,
    CLIENT
}

enum class ZCamScreen {
    MAIN,
    PAIRING
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
        pin = "",
        deviceId = "android-client",
        displayName = "Android Client"
    ),
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

    data object RequestPairingQr : ZCamUiAction
    data class PairingPayloadChanged(val value: String) : ZCamUiAction
    data object ApplyPairingPayload : ZCamUiAction
    data class PairingSessionIdChanged(val value: String) : ZCamUiAction
    data class PairingCodeChanged(val value: String) : ZCamUiAction
    data class PairingPinChanged(val value: String) : ZCamUiAction
    data class PairingDeviceIdChanged(val value: String) : ZCamUiAction
    data class PairingDisplayNameChanged(val value: String) : ZCamUiAction
    data object SubmitPairing : ZCamUiAction

    data object ClearError : ZCamUiAction
}
