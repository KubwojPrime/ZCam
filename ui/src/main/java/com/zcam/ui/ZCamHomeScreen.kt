package com.zcam.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderSection(state = state, onAction = onAction)

            if (state.working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            TabRow(
                selectedTabIndex = if (state.screen == ZCamScreen.MAIN) 0 else 1
            ) {
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
            }

            when (state.screen) {
                ZCamScreen.MAIN -> MainScreen(state = state, onAction = onAction)
                ZCamScreen.PAIRING -> PairingScreen(state = state, onAction = onAction)
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ModeButton(
                label = "Server",
                selected = state.mode == ZCamMode.SERVER,
                onClick = { onAction(ZCamUiAction.ModeChanged(ZCamMode.SERVER)) },
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                label = "Client",
                selected = state.mode == ZCamMode.CLIENT,
                onClick = { onAction(ZCamUiAction.ModeChanged(ZCamMode.CLIENT)) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MainScreen(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            PreviewCard(state = state)
        }
        item {
            RuntimeControls(state = state, onAction = onAction)
        }
        item {
            PushToTalkControls(state = state, onAction = onAction)
        }
        item {
            QuickSoundsSection(state = state, onAction = onAction)
        }
        item {
            ClientSection(state = state, onAction = onAction)
        }
        item {
            Text(
                text = "Component status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        items(state.componentStatuses) { component ->
            ComponentStatusCard(component = component)
        }
        if (!state.errorMessage.isNullOrBlank()) {
            item {
                ErrorCard(message = state.errorMessage, onDismiss = { onAction(ZCamUiAction.ClearError) })
            }
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
        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            onClick = { onAction(ZCamUiAction.RequestPairingQr) }
        ) {
            Text("Generate / Refresh QR")
        }

        StatusChip(
            label = if (state.pairing.sessionId.isBlank()) "No active pairing challenge" else "Session: ${state.pairing.sessionId}",
            tone = if (state.pairing.sessionId.isBlank()) StatusTone.NEUTRAL else StatusTone.HEALTHY
        )
        StatusChip(
            label = if (state.pairing.pairingCode.isBlank()) "Code unavailable" else "Code: ${state.pairing.pairingCode}",
            tone = if (state.pairing.pairingCode.isBlank()) StatusTone.WARNING else StatusTone.HEALTHY
        )

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.pairing.pin,
            onValueChange = { onAction(ZCamUiAction.PairingPinChanged(it)) },
            label = { Text("PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.pairing.deviceId,
            onValueChange = { onAction(ZCamUiAction.PairingDeviceIdChanged(it)) },
            label = { Text("Device ID") }
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = state.pairing.displayName,
            onValueChange = { onAction(ZCamUiAction.PairingDisplayNameChanged(it)) },
            label = { Text("Display name") }
        )

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            onClick = { onAction(ZCamUiAction.SubmitPairing) }
        ) {
            Text("Pair device")
        }

        if (state.pairing.qrPayload.isNotBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    text = state.pairing.qrPayload,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (state.pairing.resultMessage.isNotBlank()) {
            StatusChip(
                label = state.pairing.resultMessage,
                tone = state.pairing.resultTone
            )
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
            Text(text = state.previewLabel, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RuntimeControls(
    state: ZCamUiState,
    onAction: (ZCamUiAction) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = { onAction(ZCamUiAction.StartRuntime) },
            modifier = Modifier
                .weight(1f)
                .height(58.dp)
        ) {
            Text("Start")
        }
        OutlinedButton(
            onClick = { onAction(ZCamUiAction.StopRuntime) },
            modifier = Modifier
                .weight(1f)
                .height(58.dp)
        ) {
            Text("Stop")
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
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(container, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = content, fontWeight = FontWeight.SemiBold)
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
