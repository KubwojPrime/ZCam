package com.zcam.security

enum class PairingClientType {
    ANDROID_APP,
    WEB_BROWSER
}

data class PendingPairingRequest(
    val requestId: String,
    val deviceId: String,
    val displayName: String,
    val clientType: PairingClientType,
    val verificationCode: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

sealed interface PairingRequestStartResult {
    data class Success(
        val requestId: String,
        val deviceId: String,
        val displayName: String,
        val expiresAtEpochMs: Long
    ) : PairingRequestStartResult

    data class Failure(
        val statusCode: Int,
        val reason: String,
        val retryAfterSeconds: Int? = null,
        val message: String? = null
    ) : PairingRequestStartResult
}

sealed interface PairingActionResult {
    data class Success(
        val message: String = "ok"
    ) : PairingActionResult

    data class Failure(
        val statusCode: Int,
        val reason: String
    ) : PairingActionResult
}

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
        val reason: String,
        val retryAfterSeconds: Int? = null,
        val message: String? = null
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
