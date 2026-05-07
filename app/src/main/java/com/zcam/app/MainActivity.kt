package com.zcam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.zcam.ui.ZCamHomeScreen
import com.zcam.ui.ZCamMode
import com.zcam.ui.ZCamUiAction
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: ZCamMainViewModel by viewModels()
    private var lastHandledPayload: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handlePairingIntent(intent)

        setContent {
            val state by viewModel.state.collectAsState()
            var permissionRefreshNonce by remember { mutableIntStateOf(0) }
            var lastAutoRequestedMode by remember { mutableStateOf<ZCamMode?>(null) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                permissionRefreshNonce++
            }

            val requiredPermissions = remember(state.mode) { requiredPermissionsForMode(state.mode) }
            val missingPermissions = remember(state.mode, permissionRefreshNonce) {
                requiredPermissions.filterNot(::isPermissionGranted)
            }

            LaunchedEffect(state.mode) {
                if (lastAutoRequestedMode != state.mode && missingPermissions.isNotEmpty()) {
                    lastAutoRequestedMode = state.mode
                    permissionLauncher.launch(missingPermissions.toTypedArray())
                }
            }

            val uiState = state.copy(
                permissionsReady = missingPermissions.isEmpty(),
                permissionsLabel = permissionLabel(state.mode, missingPermissions)
            )

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

            ZCamHomeScreen(state = uiState, onAction = onAction)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
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
}
