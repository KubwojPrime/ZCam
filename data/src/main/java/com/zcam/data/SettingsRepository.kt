package com.zcam.data

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<ZCamSettings>

    suspend fun updatePin(pin: String)
    suspend fun updateToken(token: String)
    suspend fun updateServerPort(port: Int)
}
