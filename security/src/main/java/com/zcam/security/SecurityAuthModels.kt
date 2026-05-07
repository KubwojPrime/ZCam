package com.zcam.security

data class SecurityAuthDecision(
    val allowed: Boolean,
    val statusCode: Int,
    val reason: String,
    val tokenId: String? = null,
    val deviceId: String? = null
)

data class PairingChallenge(
    val sessionId: String,
    val pairingCode: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

sealed interface PairingResult {
    data class Success(
        val tokenId: String,
        val tokenValue: String,
        val deviceId: String
    ) : PairingResult

    data class Failure(
        val statusCode: Int,
        val reason: String
    ) : PairingResult
}

sealed interface TokenRotationResult {
    data class Success(
        val tokenId: String,
        val tokenValue: String
    ) : TokenRotationResult

    data class Failure(
        val statusCode: Int,
        val reason: String
    ) : TokenRotationResult
}

sealed interface TokenRevocationResult {
    data class Success(
        val revokedTokenId: String
    ) : TokenRevocationResult

    data class Failure(
        val statusCode: Int,
        val reason: String
    ) : TokenRevocationResult
}
