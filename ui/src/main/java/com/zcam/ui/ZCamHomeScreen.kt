package com.zcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.zcam.core.domain.config.FeatureFlag
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZCamHomeScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (state.showModePicker) {
                ModePickerScreen(onAction = onAction)
            } else {
                val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        AppDrawerContent(
                            state = state,
                            onScreenSelected = { screen ->
                                scope.launch {
                                    drawerState.close()
                                    onAction(ZCamUiAction.ScreenChanged(screen))
                                }
                            },
                            onChangeMode = {
                                scope.launch {
                                    drawerState.close()
                                    onAction(ZCamUiAction.OpenModePicker)
                                }
                            }
                        )
                    }
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(screenTitle(state = state))
                                },
                                navigationIcon = {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                drawerState.open()
                                            }
                                        }
                                    ) {
                                        Text("\u2630", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            )
                        }
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            if (state.working || state.settings.saving) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (!state.permissionsReady) {
                                PermissionNoticeCard(state = state, onAction = onAction)
                            }

                            when (state.screen) {
                                ZCamScreen.MAIN -> MainScreen(state = state, onAction = onAction)
                                ZCamScreen.PAIRING -> PairingScreen(state = state, onAction = onAction)
                                ZCamScreen.SETTINGS -> SettingsScreen(state = state, onAction = onAction)
                            }
                        }
                    }
                }
            }

            if (state.showPairingSuggestionDialog && state.mode == ZCamMode.SERVER && !state.showModePicker) {
                PairingSuggestionDialog(onAction = onAction)
            }
        }
    }
}

@Composable
private fun ModePickerScreen(
    onAction: (ZCamUiAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = "Choose device role",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Select how this device should work right now.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            onClick = { onAction(ZCamUiAction.ModeChanged(ZCamMode.SERVER)) }
        ) {
            Text("Server")
        }
        OutlinedButton(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            onClick = { onAction(ZCamUiAction.ModeChanged(ZCamMode.CLIENT)) }
        ) {
            Text("Client")
        }
    }
}

@Composable
private fun AppDrawerContent(
    state: ZCamUiState,
    onScreenSelected: (ZCamScreen) -> Unit,
    onChangeMode: () -> Unit
) {
    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("ZCam", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                if (state.mode == ZCamMode.SERVER) "Server device" else "Client remote",
                style = MaterialTheme.typography.bodySmall
            )
            NavigationDrawerItem(
                label = { Text("Main") },
                selected = state.screen == ZCamScreen.MAIN,
                onClick = { onScreenSelected(ZCamScreen.MAIN) }
            )
            NavigationDrawerItem(
                label = { Text("Pairing") },
                selected = state.screen == ZCamScreen.PAIRING,
                onClick = { onScreenSelected(ZCamScreen.PAIRING) }
            )
            NavigationDrawerItem(
                label = { Text("Settings") },
                selected = state.screen == ZCamScreen.SETTINGS,
                onClick = { onScreenSelected(ZCamScreen.SETTINGS) }
            )
            NavigationDrawerItem(
                label = { Text("Change Mode") },
                selected = false,
                onClick = onChangeMode
            )
        }
    }
}

private fun screenTitle(state: ZCamUiState): String {
    return when (state.screen) {
        ZCamScreen.MAIN -> if (state.mode == ZCamMode.SERVER) "Server" else "Client"
        ZCamScreen.PAIRING -> "Pairing"
        ZCamScreen.SETTINGS -> "Settings"
    }
}

@Composable
private fun PermissionNoticeCard(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = state.permissionsLabel,
                style = MaterialTheme.typography.bodyMedium
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onAction(ZCamUiAction.RequestPermissions) }
            ) {
                Text("Grant permissions")
            }
        }
    }
}

@Composable
private fun HeaderSection(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "ZCam Control",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        StatusChip(label = "Runtime: ${state.runtimeLabel}", tone = state.runtimeTone)
        StatusChip(label = "Thermal: ${state.thermalLabel}", tone = state.thermalTone)
        StatusChip(label = "Recovery: ${state.recoveryLabel}", tone = state.recoveryTone)
        StatusChip(
            label = state.permissionsLabel,
            tone = if (state.permissionsReady) StatusTone.HEALTHY else StatusTone.WARNING
        )
        if (!state.permissionsReady) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onAction(ZCamUiAction.RequestPermissions) }
            ) {
                Text("Grant required permissions")
            }
        }
    }
}

@Composable
private fun ModeTabs(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    TabRow(
        selectedTabIndex = if (state.mode == ZCamMode.SERVER) 0 else 1
    ) {
        Tab(
            selected = state.mode == ZCamMode.SERVER,
            onClick = { onAction(ZCamUiAction.ModeChanged(ZCamMode.SERVER)) },
            text = { Text("Server") }
        )
        Tab(
            selected = state.mode == ZCamMode.CLIENT,
            onClick = { onAction(ZCamUiAction.ModeChanged(ZCamMode.CLIENT)) },
            text = { Text("Client") }
        )
    }
}

@Composable
private fun ScreenTabs(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    val selected = when (state.screen) {
        ZCamScreen.MAIN -> 0
        ZCamScreen.PAIRING -> 1
        ZCamScreen.SETTINGS -> 2
    }
    TabRow(selectedTabIndex = selected) {
        Tab(
            selected = state.screen == ZCamScreen.MAIN,
            onClick = { onAction(ZCamUiAction.ScreenChanged(ZCamScreen.MAIN)) },
            text = { Text("Main") }
        )
        Tab(
            selected = state.screen == ZCamScreen.PAIRING,
            onClick = { onAction(ZCamUiAction.ScreenChanged(ZCamScreen.PAIRING)) },
            text = { Text("Pairing") }
        )
        Tab(
            selected = state.screen == ZCamScreen.SETTINGS,
            onClick = { onAction(ZCamUiAction.ScreenChanged(ZCamScreen.SETTINGS)) },
            text = { Text("Settings") }
        )
    }
}

@Composable
private fun MainScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (state.mode) {
            ZCamMode.SERVER -> {
                PreviewCard(state = state)
                RuntimeControls(state = state, onAction = onAction)
            }
            ZCamMode.CLIENT -> {
                ClientSection(state = state, onAction = onAction)
                PreviewCard(state = state)
                PushToTalkControls(state = state, onAction = onAction)
                QuickSoundsSection(state = state, onAction = onAction)
                RecordingsSection(state = state, onAction = onAction)
            }
        }

        if (!state.errorMessage.isNullOrBlank()) {
            ErrorCard(message = state.errorMessage, onDismiss = { onAction(ZCamUiAction.ClearError) })
        }
    }
}

@Composable
private fun PairingScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.mode == ZCamMode.SERVER) {
            ServerPairingScreen(state = state, onAction = onAction)
        } else {
            ClientPairingScreen(state = state, onAction = onAction)
        }
        if (!state.errorMessage.isNullOrBlank()) {
            ErrorCard(message = state.errorMessage, onDismiss = { onAction(ZCamUiAction.ClearError) })
        }
    }
}

@Composable
private fun ServerPairingScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    InfoCard(
        title = "Server pairing",
        body = "Clients and browsers start pairing from their side. This screen shows pending pairing requests and the verification code to enter on the client."
    )
    StatusChip(
        label = if (state.serverLanHost.isBlank()) {
            "LAN host not detected"
        } else {
            "LAN host: ${state.serverLanHost}:${state.settings.serverPortInput}"
        },
        tone = if (state.serverLanHost.isBlank()) StatusTone.WARNING else StatusTone.HEALTHY
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            onClick = { onAction(ZCamUiAction.RequestPairingQr) }
        ) {
            Text("Show connection QR")
        }
        OutlinedButton(
            modifier = Modifier
                .weight(1f)
                .height(56.dp),
            onClick = { onAction(ZCamUiAction.ScreenChanged(ZCamScreen.SETTINGS)) }
        ) {
            Text("Open settings")
        }
    }

    if (state.pairing.qrPayload.isNotBlank()) {
        PairingQrCard(state.pairing.qrPayload)
    }
    if (state.pendingPairingRequests.isEmpty()) {
        InfoCard(
            title = "No pending requests",
            body = "Open the client app or browser, start pairing, then enter the code shown here on the client device."
        )
    } else {
        state.pendingPairingRequests.forEach { request ->
            PendingPairingRequestCard(request = request, onCancel = {
                onAction(ZCamUiAction.CancelPendingPairing(request.requestId))
            })
        }
    }
}

@Composable
private fun ClientPairingScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    InfoCard(
        title = "Client pairing",
        body = "Set server Host/Port or scan connection QR, request pairing, then enter the code currently shown on the server device."
    )
    ClientSection(state = state, onAction = onAction)

    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = state.pairing.payloadInput,
        onValueChange = { onAction(ZCamUiAction.PairingPayloadChanged(it)) },
        label = { Text("Pairing payload (optional)") },
        placeholder = { Text("zcam://pair?host=...") }
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            enabled = !state.pairing.loading,
            onClick = { onAction(ZCamUiAction.ApplyPairingPayload) }
        ) {
            Text("Apply payload")
        }
        OutlinedButton(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            onClick = { onAction(ZCamUiAction.RefreshClientStatus) }
        ) {
            Text("Check server")
        }
    }

    PairingMetaCard(state = state)
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = state.pairing.deviceId,
        onValueChange = { onAction(ZCamUiAction.PairingDeviceIdChanged(it)) },
        label = { Text("Device ID") },
        supportingText = { Text("Stable technical identifier for this client device.") },
        singleLine = true
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = state.pairing.displayName,
        onValueChange = { onAction(ZCamUiAction.PairingDisplayNameChanged(it)) },
        label = { Text("Display name") },
        supportingText = { Text("Human-friendly name shown in trusted device list.") },
        singleLine = true
    )
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = !state.pairing.loading,
        onClick = { onAction(ZCamUiAction.StartPairingRequest) }
    ) {
        Text(if (state.pairing.loading) "Requesting..." else "Start pairing request")
    }
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = state.pairing.verificationCodeInput,
        onValueChange = { onAction(ZCamUiAction.PairingVerificationCodeChanged(it)) },
        label = { Text("Code shown on server") },
        supportingText = { Text("Enter the 6-digit code currently visible on the server device.") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true
    )
    Button(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = !state.pairing.loading,
        onClick = { onAction(ZCamUiAction.SubmitPairing) }
    ) {
        Text(if (state.pairing.loading) "Pairing..." else "Complete pairing")
    }
}

@Composable
private fun SettingsScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    val settings = state.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (state.mode == ZCamMode.CLIENT) {
            InfoCard(
                title = "Client settings",
                body = "Server runtime settings are editable only in Server mode. Client host, pairing and audio controls stay on the main and pairing screens."
            )
        }

        if (state.mode == ZCamMode.SERVER) {
            SettingsSection(title = "Network & Security") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settings.serverPortInput,
                    onValueChange = { onAction(ZCamUiAction.SettingsServerPortChanged(it)) },
                    label = { Text("Server port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settings.pinInput,
                    onValueChange = { onAction(ZCamUiAction.SettingsPinChanged(it)) },
                    label = { Text("Legacy pairing PIN (fallback)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settings.apiTokenInput,
                    onValueChange = { onAction(ZCamUiAction.SettingsApiTokenChanged(it)) },
                    label = { Text("Bootstrap API token (advanced)") },
                    singleLine = true
                )
            }

            SettingsSection(title = "Video stream") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = settings.streamWidthInput,
                        onValueChange = { onAction(ZCamUiAction.SettingsStreamWidthChanged(it)) },
                        label = { Text("Width") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = settings.streamHeightInput,
                        onValueChange = { onAction(ZCamUiAction.SettingsStreamHeightChanged(it)) },
                        label = { Text("Height") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = settings.streamFpsInput,
                        onValueChange = { onAction(ZCamUiAction.SettingsStreamFpsChanged(it)) },
                        label = { Text("FPS") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        modifier = Modifier.weight(1f),
                        value = settings.streamCodecLabel,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Codec") },
                        singleLine = true
                    )
                }
            }

            SettingsSection(title = "Recording") {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settings.segmentMinutesInput,
                    onValueChange = { onAction(ZCamUiAction.SettingsSegmentMinutesChanged(it)) },
                    label = { Text("Segment duration (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settings.maxStorageGbInput,
                    onValueChange = { onAction(ZCamUiAction.SettingsMaxStorageGbChanged(it)) },
                    label = { Text("Max storage (GB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = settings.minFreeStorageGbInput,
                    onValueChange = { onAction(ZCamUiAction.SettingsMinFreeStorageGbChanged(it)) },
                    label = { Text("Minimum free storage (GB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }

            SettingsSection(title = "Feature flags") {
                FeatureFlagRow(
                    label = "MJPEG streaming",
                    checked = settings.mjpegStreamingEnabled,
                    onCheckedChange = { onAction(ZCamUiAction.SettingsFlagChanged(FeatureFlag.MJPEG_STREAMING, it)) }
                )
                FeatureFlagRow(
                    label = "Loop recording",
                    checked = settings.loopRecordingEnabled,
                    onCheckedChange = { onAction(ZCamUiAction.SettingsFlagChanged(FeatureFlag.LOOP_RECORDING, it)) }
                )
                FeatureFlagRow(
                    label = "Audio push-to-talk",
                    checked = settings.audioPushToTalkEnabled,
                    onCheckedChange = { onAction(ZCamUiAction.SettingsFlagChanged(FeatureFlag.AUDIO_PUSH_TO_TALK, it)) }
                )
                FeatureFlagRow(
                    label = "Audio live",
                    checked = settings.audioLiveEnabled,
                    onCheckedChange = { onAction(ZCamUiAction.SettingsFlagChanged(FeatureFlag.AUDIO_LIVE, it)) }
                )
                FeatureFlagRow(
                    label = "Audio playback",
                    checked = settings.audioPlaybackEnabled,
                    onCheckedChange = { onAction(ZCamUiAction.SettingsFlagChanged(FeatureFlag.AUDIO_PLAYBACK, it)) }
                )
                FeatureFlagRow(
                    label = "Trusted devices (policy locked)",
                    checked = settings.trustedDevicesEnabled,
                    enabled = false,
                    onCheckedChange = { onAction(ZCamUiAction.SettingsFlagChanged(FeatureFlag.TRUSTED_DEVICES, it)) }
                )
                FeatureFlagRow(
                    label = "Watchdog recovery",
                    checked = settings.watchdogRecoveryEnabled,
                    onCheckedChange = { onAction(ZCamUiAction.SettingsFlagChanged(FeatureFlag.WATCHDOG_RECOVERY, it)) }
                )
            }

            SettingsSection(title = "Trusted devices") {
                if (settings.trustedDevices.isEmpty()) {
                    Text("No trusted devices yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    settings.trustedDevices.forEach { device ->
                        TrustedDeviceRow(
                            device = device,
                            onRevoke = { onAction(ZCamUiAction.RevokeTrustedDevice(device.deviceId)) }
                        )
                    }
                }
            }

            SettingsSection(title = "Diagnostics") {
                StatusChip(label = "Runtime: ${state.runtimeLabel}", tone = state.runtimeTone)
                StatusChip(label = "Thermal: ${state.thermalLabel}", tone = state.thermalTone)
                StatusChip(label = "Recovery: ${state.recoveryLabel}", tone = state.recoveryTone)
                state.componentStatuses.forEach { component ->
                    ComponentStatusCard(component = component)
                }
            }

            if (settings.resultMessage.isNotBlank()) {
                StatusChip(
                    label = settings.resultMessage,
                    tone = settings.resultTone
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = !settings.saving,
                onClick = { onAction(ZCamUiAction.SaveSettings) }
            ) {
                Text(if (settings.saving) "Saving..." else "Save settings")
            }
        }

        if (!state.errorMessage.isNullOrBlank()) {
            ErrorCard(message = state.errorMessage, onDismiss = { onAction(ZCamUiAction.ClearError) })
        }
    }
}

@Composable
private fun PairingSuggestionDialog(
    onAction: (ZCamUiAction) -> Unit
) {
    AlertDialog(
        onDismissRequest = { onAction(ZCamUiAction.DismissPairingSuggestion) },
        confirmButton = {
            TextButton(onClick = { onAction(ZCamUiAction.OpenPairingFromSuggestion) }) {
                Text("Open pairing")
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(ZCamUiAction.DismissPairingSuggestion) }) {
                Text("Later")
            }
        },
        title = { Text("Server started") },
        text = { Text("Do you want to pair a client device now? You can also do it later from Pairing in the menu.") }
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                content()
            }
        )
    }
}

@Composable
private fun FeatureFlagRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun TrustedDeviceRow(
    device: TrustedDeviceUi,
    onRevoke: () -> Unit
) {
    val addedAt = remember(device.addedAtEpochMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(device.addedAtEpochMillis))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(device.displayName, fontWeight = FontWeight.SemiBold)
            Text("Device ID: ${device.deviceId}", style = MaterialTheme.typography.bodySmall)
            Text("Added: $addedAt", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRevoke
            ) {
                Text("Revoke device")
            }
        }
    }
}

@Composable
private fun PairingMetaCard(state: ZCamUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StatusChip(
                label = state.pairing.sourceLabel,
                tone = StatusTone.NEUTRAL
            )
            if (state.pairing.resolvedHostPort.isNotBlank()) {
                Text("Host: ${state.pairing.resolvedHostPort}")
            }
            Text("Request ID: ${state.pairing.requestId.ifBlank { "-" }}")
            if (state.pairing.issuedToken.isNotBlank()) {
                Text("Trusted device token saved", style = MaterialTheme.typography.bodySmall)
            }
            if (state.pairing.resultMessage.isNotBlank()) {
                StatusChip(
                    label = state.pairing.resultMessage,
                    tone = state.pairing.resultTone
                )
            }
        }
    }
}

@Composable
private fun PendingPairingRequestCard(
    request: PendingPairingRequestUi,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(request.displayName, fontWeight = FontWeight.SemiBold)
            Text("${request.clientTypeLabel} - ${request.deviceId}", style = MaterialTheme.typography.bodySmall)
            Text("Code: ${request.verificationCode}", style = MaterialTheme.typography.titleLarge)
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCancel
            ) {
                Text("Cancel request")
            }
        }
    }
}

@Composable
private fun PairingQrCard(payload: String) {
    val qrBitmap = remember(payload) { createQrBitmap(payload, 360) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Pairing QR",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (qrBitmap != null) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Pairing QR code"
                )
            } else {
                Text(
                    text = "QR generation failed. Enter the server host manually on the client.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            Text(
                text = payload,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun createQrBitmap(payload: String, sizePx: Int): Bitmap? {
    if (payload.isBlank()) return null
    return runCatching {
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}

@Composable
private fun RoleInfoCard(mode: ZCamMode) {
    val title = if (mode == ZCamMode.SERVER) "Server mode" else "Client mode"
    val body = if (mode == ZCamMode.SERVER) {
        "Server controls are visible. Camera, runtime and system health are managed locally."
    } else {
        "Client controls are visible. Connect to server host, pair device and use audio controls."
    }
    InfoCard(title = title, body = body)
}

@Composable
private fun InfoCard(
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PreviewCard(state: ZCamUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            val bitmap = remember(state.previewFrameJpeg) {
                state.previewFrameJpeg?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Live preview",
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = "Preview unavailable",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeControls(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusChip(label = "Runtime: ${state.runtimeLabel}", tone = state.runtimeTone)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onAction(ZCamUiAction.StartRuntime) },
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp),
                    enabled = !state.working
                ) {
                    Text("Start")
                }
                OutlinedButton(
                    onClick = { onAction(ZCamUiAction.StopRuntime) },
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp),
                    enabled = !state.working
                ) {
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun PushToTalkControls(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                onClick = { onAction(ZCamUiAction.PushToTalkChanged(!state.pttPressed)) }
            ) {
                Text(if (state.pttPressed) "Release Push-To-Talk" else "Hold Push-To-Talk")
            }
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                onClick = { onAction(ZCamUiAction.ToggleLiveListen) }
            ) {
                Text(if (state.liveListenEnabled) "Disable live listen" else "Enable live listen")
            }
            Text(text = "Volume ${state.volumePercent}%")
            Slider(
                value = state.volumePercent.toFloat(),
                onValueChange = { onAction(ZCamUiAction.VolumeChanged(it.toInt())) },
                valueRange = 0f..85f
            )
        }
    }
}

@Composable
private fun QuickSoundsSection(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Quick sounds",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.quickSounds.forEach { sound ->
                Button(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    onClick = { onAction(ZCamUiAction.PlayQuickSound(sound.clipId, sound.aversive)) }
                ) {
                    Text(sound.label)
                }
            }
        }
    }
}

@Composable
private fun RecordingsSection(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Recordings",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Time range format: YYYY-MM-DD HH:mm (local) or epoch ms",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.recordings.fromInput,
                onValueChange = { onAction(ZCamUiAction.RecordingsFromChanged(it)) },
                label = { Text("From") },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.recordings.toInput,
                onValueChange = { onAction(ZCamUiAction.RecordingsToChanged(it)) },
                label = { Text("To") },
                singleLine = true
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !state.recordings.loading,
                onClick = { onAction(ZCamUiAction.FetchRecordings) }
            ) {
                Text(if (state.recordings.loading) "Loading..." else "Load recordings")
            }
            if (state.recordings.resultMessage.isNotBlank()) {
                StatusChip(
                    label = state.recordings.resultMessage,
                    tone = StatusTone.NEUTRAL
                )
            }
            if (state.recordings.items.isEmpty()) {
                Text(
                    text = "No recordings in selected range.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                state.recordings.items.forEach { item ->
                    RecordingItemRow(item = item, onPlay = { onAction(ZCamUiAction.PlayRecording(item.fileName)) })
                }
            }
        }
    }
}

@Composable
private fun RecordingItemRow(
    item: RecordingItemUi,
    onPlay: () -> Unit
) {
    val startedAt = remember(item.startedAtEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(item.startedAtEpochMs))
    }
    val endedAt = remember(item.endedAtEpochMs) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
            .format(Date(item.endedAtEpochMs))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(item.fileName, fontWeight = FontWeight.SemiBold)
            Text("Start: $startedAt", style = MaterialTheme.typography.bodySmall)
            Text("End: $endedAt", style = MaterialTheme.typography.bodySmall)
            Text(
                "Duration: ${item.durationMs / 1000}s - Size: ${item.sizeBytes / (1024 * 1024)} MB",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onPlay
            ) {
                Text("Play recording")
            }
        }
    }
}

@Composable
private fun ClientSection(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Client target",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.clientHost,
                onValueChange = { onAction(ZCamUiAction.ClientHostChanged(it)) },
                label = { Text("Host") },
                singleLine = true
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.clientPort,
                onValueChange = { onAction(ZCamUiAction.ClientPortChanged(it)) },
                label = { Text("Port") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                onClick = { onAction(ZCamUiAction.RefreshClientStatus) }
            ) {
                Text("Check connection")
            }
            StatusChip(
                label = state.clientStatusLabel,
                tone = if (state.clientReachable) StatusTone.HEALTHY else StatusTone.WARNING
            )
        }
    }
}

@Composable
private fun ComponentStatusCard(component: ComponentStatusUi) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(component.label, fontWeight = FontWeight.SemiBold)
                StatusChip(label = component.status, tone = component.tone, compact = true)
            }
            Text(component.details, style = MaterialTheme.typography.bodySmall)
            if (component.recoveryAttempts > 0) {
                Text(
                    text = "Recovery attempts: ${component.recoveryAttempts}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Error",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(text = message, color = MaterialTheme.colorScheme.onErrorContainer)
            OutlinedButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    tone: StatusTone,
    compact: Boolean = false
) {
    val (container, content) = when (tone) {
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.HEALTHY -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        StatusTone.WARNING -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(16.dp))
            .padding(
                horizontal = if (compact) 10.dp else 12.dp,
                vertical = if (compact) 4.dp else 7.dp
            )
    ) {
        Text(
            text = label,
            color = content,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
        )
    }
}


