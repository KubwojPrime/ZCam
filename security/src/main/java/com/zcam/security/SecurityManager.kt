package com.zcam.security

import com.zcam.core.domain.security.SecurityEngine

interface SecurityManager : SecurityEngine {
    suspend fun authorizeRequest(
        tokenCandidate: String?,
        deviceId: String?
    ): SecurityAuthDecision

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
