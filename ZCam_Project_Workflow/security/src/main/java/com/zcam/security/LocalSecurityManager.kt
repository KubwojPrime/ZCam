package com.zcam.security

import com.zcam.data.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalSecurityManager @Inject constructor(
    private val settingsRepository: SettingsRepository
) : SecurityManager {

    override suspend fun validateToken(candidate: String): Boolean {
        val settings = settingsRepository.settings.first()
        return settings.apiToken == candidate
    }

    override suspend fun validatePin(candidate: String): Boolean {
        val settings = settingsRepository.settings.first()
        return settings.pinCode == candidate
    }
}
