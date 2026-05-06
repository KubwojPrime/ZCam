package com.zcam.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import com.zcam.service.ZCamForegroundService
import com.zcam.ui.ZCamHomeScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ZCamHomeScreen(
                onStartService = {
                    val intent = ZCamForegroundService.startIntent(this)
                    ContextCompat.startForegroundService(this, intent)
                },
                onStopService = {
                    val intent: Intent = ZCamForegroundService.stopIntent(this)
                    startService(intent)
                }
            )
        }
    }
}
