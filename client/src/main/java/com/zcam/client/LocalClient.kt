package com.zcam.client

data class ClientTarget(
    val host: String,
    val port: Int,
    val token: String? = null,
    val deviceId: String? = null
)

data class ClientServerStatus(
    val alive: Boolean,
    val overallStatus: String,
    val streamClients: Int,
    val uptimeMs: Long,
    val videoRunning: Boolean,
    val lastFrameAgeMs: Long,
    val audioTransmitting: Boolean,
    val audioLiveListening: Boolean,
    val audioPlayingBack: Boolean,
    val audioVolumePercent: Int?,
    val audioMinVolumePercent: Int?,
    val audioMaxVolumePercent: Int?
)

data class ClientPairingQr(
    val sessionId: String,
    val pairingCode: String,
    val qrPayload: String,
    val expiresAtEpochMs: Long
)

data class ClientPairingRequest(
    val requestId: String,
    val deviceId: String,
    val displayName: String,
    val expiresAtEpochMs: Long
)

data class ClientPairingResult(
    val tokenId: String,
    val tokenValue: String,
    val deviceId: String
)

data class ClientRecordingSummary(
    val fileName: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val container: String,
    val codec: String
)

sealed interface ClientCallResult<out T> {
    data class Success<T>(val value: T) : ClientCallResult<T>

    data class Failure(
        val code: Int?,
        val reason: String,
        val responseBody: String? = null
    ) : ClientCallResult<Nothing>
}

interface LocalClient {
    suspend fun isServerAlive(target: ClientTarget): Boolean
    suspend fun fetchStatus(target: ClientTarget): ClientCallResult<ClientServerStatus>
    suspend fun fetchSnapshot(target: ClientTarget): ClientCallResult<ByteArray>
    suspend fun setPushToTalk(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit>
    suspend fun setLiveListen(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit>
    suspend fun playQuickSound(
        target: ClientTarget,
        clipId: String,
        aversive: Boolean
    ): ClientCallResult<Unit>

    suspend fun setVolume(target: ClientTarget, levelPercent: Int): ClientCallResult<Unit>
    suspend fun fetchPairingQr(target: ClientTarget): ClientCallResult<ClientPairingQr>
    suspend fun requestPairing(
        target: ClientTarget,
        deviceId: String,
        displayName: String,
        clientType: String
    ): ClientCallResult<ClientPairingRequest>

    suspend fun completePairingRequest(
        target: ClientTarget,
        requestId: String,
        verificationCode: String
    ): ClientCallResult<ClientPairingResult>

    suspend fun pairDevice(
        target: ClientTarget,
        pin: String,
        sessionId: String,
        pairingCode: String,
        deviceId: String,
        displayName: String
    ): ClientCallResult<ClientPairingResult>

    suspend fun fetchRecordings(
        target: ClientTarget,
        fromEpochMs: Long? = null,
        toEpochMs: Long? = null,
        limit: Int = 120
    ): ClientCallResult<List<ClientRecordingSummary>>

    fun buildRecordingPlaybackUrl(target: ClientTarget, fileName: String): String
}
