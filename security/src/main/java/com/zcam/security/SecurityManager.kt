package com.zcam.security

import com.zcam.core.domain.security.SecurityEngine
import kotlinx.coroutines.flow.Flow

interface SecurityManager : SecurityEngine {
    val pendingPairingRequests: Flow<List<PendingPairingRequest>>

    suspend fun authorizeRequest(
        tokenCandidate: String?,
        deviceId: String?
    ): SecurityAuthDecision

    suspend fun requestPairing(
        deviceId: String,
        displayName: String,
        clientType: PairingClientType
    ): PairingRequestStartResult

    suspend fun completePairingRequest(
        requestId: String,
        verificationCode: String
    ): PairingResult

    suspend fun cancelPairingRequest(requestId: String): PairingActionResult

    suspend fun createPairingChallenge(): PairingChallenge

    suspend fun pairDevice(
        pin: String,
        sessionId: String,
        pairingCode: String,
        deviceId: String,
        displayName: String
    ): PairingResult

    suspend fun rotateToken(
        requesterToken: String?,
        requesterDeviceId: String?,
        revokeCurrent: Boolean = true
    ): TokenRotationResult

    suspend fun revokeToken(
        requesterToken: String?,
        requesterDeviceId: String?,
        tokenIdToRevoke: String
    ): TokenRevocationResult

    suspend fun sanityCheckAfterRestart()
}
