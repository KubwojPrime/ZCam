package com.zcam.service.runtime

import kotlinx.coroutines.flow.StateFlow

enum class ThermalBand {
    NOMINAL,
    THROTTLED,
    CRITICAL
}

data class NetworkConnectivity(
    val connected: Boolean,
    val transport: String = "unknown"
)

interface RuntimeEnvironmentMonitor {
    val thermalBand: StateFlow<ThermalBand>
    val networkConnectivity: StateFlow<NetworkConnectivity>

    suspend fun start()
    suspend fun stop()
}
