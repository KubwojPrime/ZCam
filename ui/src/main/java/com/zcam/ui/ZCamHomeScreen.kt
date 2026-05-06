package com.zcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ZCamHomeScreen(
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "ZCam", style = MaterialTheme.typography.headlineMedium)
            Text(text = "LAN-only camera runtime", modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))

            Button(onClick = onStartService) {
                Text("Start Runtime")
            }

            Button(
                onClick = onStopService,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Text("Stop Runtime")
            }
        }
    }
}
