package com.zcam.client

sealed interface LocalAudioTransportResult {
    data object Success : LocalAudioTransportResult

    data class Failure(
        val reason: String,
        val detail: String? = null
    ) : LocalAudioTransportResult
}

interface LocalAudioTransport {
    suspend fun startLiveListen(target: ClientTarget): LocalAudioTransportResult
    suspend fun stopLiveListen()
    suspend fun startPushToTalk(target: ClientTarget): LocalAudioTransportResult
    suspend fun stopPushToTalk()
    suspend fun stopAll()
}
