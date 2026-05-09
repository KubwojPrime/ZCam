package com.zcam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.zcam.ui.ZCamHomeScreen
import com.zcam.ui.ZCamMode
import com.zcam.ui.ZCamUiAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: ZCamMainViewModel by viewModels()
    private var lastHandledPayload: String? = null
    private var lastInteractionAtElapsedMs by mutableLongStateOf(SystemClock.elapsedRealtime())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePairingIntent(intent)
        lastInteractionAtElapsedMs = SystemClock.elapsedRealtime()

        setContent {
            val state by viewModel.state.collectAsState()
            val interactionAtElapsedMs = lastInteractionAtElapsedMs
            var permissionRefreshNonce by remember { mutableIntStateOf(0) }
            var lastAutoRequestedMode by remember { mutableStateOf<ZCamMode?>(null) }
            var serverIdleVisualState by remember { mutableStateOf(ServerIdleVisualState.ACTIVE) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                permissionRefreshNonce++
            }

            val requiredPermissions = remember(state.mode, state.showModePicker) {
                if (state.showModePicker) emptyList() else requiredPermissionsForMode(state.mode)
            }
            val missingPermissions = remember(state.mode, state.showModePicker, permissionRefreshNonce) {
                requiredPermissions.filterNot(::isPermissionGranted)
            }

            LaunchedEffect(state.showModePicker) {
                if (state.showModePicker) {
                    lastAutoRequestedMode = null
                }
            }

            LaunchedEffect(state.mode, state.showModePicker, missingPermissions) {
                if (!state.showModePicker && lastAutoRequestedMode != state.mode && missingPermissions.isNotEmpty()) {
                    lastAutoRequestedMode = state.mode
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                }
            }

            val uiState = state.copy(
                permissionsReady = missingPermissions.isEmpty(),
                permissionsLabel = permissionLabel(state.mode, missingPermissions)
            )

            val keepScreenOn = state.mode == ZCamMode.SERVER && state.runtimeOn
            DisposableEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    if (keepScreenOn) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }

            LaunchedEffect(state.mode, state.runtimeOn, state.showModePicker, interactionAtElapsedMs) {
                serverIdleVisualState = ServerIdleVisualState.ACTIVE
                if (!shouldApplyServerIdlePolicy(state)) {
                    return@LaunchedEffect
                }
                delay(SERVER_IDLE_DIM_DELAY_MS)
                serverIdleVisualState = ServerIdleVisualState.DIMMED
                delay(SERVER_IDLE_BLACKOUT_DELAY_MS - SERVER_IDLE_DIM_DELAY_MS)
                serverIdleVisualState = ServerIdleVisualState.BLACKOUT
            }

            DisposableEffect(serverIdleVisualState, state.mode, state.runtimeOn, state.showModePicker) {
                val attributes = window.attributes
                attributes.screenBrightness = if (shouldApplyServerIdlePolicy(state)) {
                    when (serverIdleVisualState) {
                        ServerIdleVisualState.ACTIVE -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        ServerIdleVisualState.DIMMED,
                        ServerIdleVisualState.BLACKOUT -> 0f
                    }
                } else {
                    WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                window.attributes = attributes
                onDispose {
                    val resetAttributes = window.attributes
                    resetAttributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    window.attributes = resetAttributes
                }
            }

            val onAction: (ZCamUiAction) -> Unit = onAction@{ action ->
                val needed = requiredPermissionsForAction(state.mode, action)
                val missingForAction = needed.filterNot(::isPermissionGranted)
                if (action == ZCamUiAction.RequestPermissions || missingForAction.isNotEmpty()) {
                    val toRequest = if (action == ZCamUiAction.RequestPermissions) {
                        missingPermissions
                    } else {
                        missingForAction
                    }
                    if (toRequest.isNotEmpty()) {
                        permissionLauncher.launch(toRequest.toTypedArray())
                        return@onAction
                    }
                }
                viewModel.onAction(action)
            }

            val blackoutActive = shouldApplyServerIdlePolicy(uiState) &&
                serverIdleVisualState == ServerIdleVisualState.BLACKOUT

            Box(modifier = Modifier.fillMaxSize()) {
                ZCamHomeScreen(state = uiState, onAction = onAction)
                if (blackoutActive) {
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = {}
                            )
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastInteractionAtElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun requiredPermissionsForMode(mode: ZCamMode): List<String> {
        return when (mode) {
            ZCamMode.SERVER -> listOf(Manifest.permission.CAMERA) + storagePermissions()
            ZCamMode.CLIENT -> listOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requiredPermissionsForAction(mode: ZCamMode, action: ZCamUiAction): List<String> {
        return when (action) {
            ZCamUiAction.StartRuntime -> if (mode == ZCamMode.SERVER) {
                listOf(Manifest.permission.CAMERA) + storagePermissions()
            } else {
                emptyList()
            }

            is ZCamUiAction.PushToTalkChanged,
            ZCamUiAction.ToggleLiveListen -> if (mode == ZCamMode.CLIENT) {
                listOf(Manifest.permission.RECORD_AUDIO)
            } else {
                emptyList()
            }

            else -> emptyList()
        }
    }

    private fun storagePermissions(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= 33 -> listOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO
            )

            Build.VERSION.SDK_INT >= 29 -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)

            else -> listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    private fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionLabel(mode: ZCamMode, missing: List<String>): String {
        if (missing.isEmpty()) return "Permissions ready"
        val prefix = if (mode == ZCamMode.SERVER) "Server requires" else "Client requires"
        val labels = missing.joinToString(", ") { permissionDisplayName(it) }
        return "$prefix: $labels"
    }

    private fun permissionDisplayName(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "Camera"
            Manifest.permission.RECORD_AUDIO -> "Microphone"
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Files"
            else -> permission
        }
    }

    private fun handlePairingIntent(intent: Intent?) {
        val payload = intent?.dataString?.trim().orEmpty()
        if (payload.isBlank()) return
        if (payload == lastHandledPayload) return
        lastHandledPayload = payload
        viewModel.onExternalPairingPayload(payload)
    }

    private fun shouldApplyServerIdlePolicy(state: com.zcam.ui.ZCamUiState): Boolean {
        return !state.showModePicker && state.mode == ZCamMode.SERVER && state.runtimeOn
    }

    private enum class ServerIdleVisualState {
        ACTIVE,
        DIMMED,
        BLACKOUT
    }

    private companion object {
        const val SERVER_IDLE_DIM_DELAY_MS = 60_000L
        const val SERVER_IDLE_BLACKOUT_DELAY_MS = 300_000L
    }
}
