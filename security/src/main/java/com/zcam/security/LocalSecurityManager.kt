package com.zcam.security

import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalSecurityManager @Inject constructor(
    private val runtimeSettingsRepository: RuntimeSettingsRepository,
    private val tokenStore: SecurityTokenStore,
    private val logger: ZCamLogger
) : SecurityManager {

    private val pendingPairingRequestsState = MutableStateFlow<List<PendingPairingRequest>>(emptyList())
    override val pendingPairingRequests = pendingPairingRequestsState.asStateFlow()

    private val sanityMutex = Mutex()
    private val tokenMutex = Mutex()
    private val pairingMutex = Mutex()
    private val random = SecureRandom()

    private var sanityChecked: Boolean = false
    private val pendingRequestsById = LinkedHashMap<String, PendingPairingRequest>()
    private val pairingSessions = LinkedHashMap<String, PairingSession>()

    override suspend fun validateToken(candidate: String): Boolean {
        if (candidate.isBlank()) return false
        ensureSanityCheck()
        val tokenHash = hashToken(candidate)
        return tokenStore.readTokens().any { it.isActive && it.tokenHash == tokenHash }
    }

    override suspend fun validatePin(candidate: String): Boolean {
        ensureSanityCheck()
        val settings = runtimeSettingsRepository.settings.first()
        return settings.security.pinCode == candidate
    }

    override suspend fun isTrustedDevice(deviceId: String): Boolean {
        ensureSanityCheck()
        val settings = runtimeSettingsRepository.settings.first()
        if (!settings.featureFlags.trustedDevices) return true
        return settings.security.trustedDevices.any { it.deviceId == deviceId }
    }

    override suspend fun trustedDevices(): Set<TrustedDevice> {
        ensureSanityCheck()
        return runtimeSettingsRepository.settings.first().security.trustedDevices
    }

    override suspend fun registerTrustedDevice(device: TrustedDevice) {
        ensureSanityCheck()
        when (val result = runtimeSettingsRepository.upsertTrustedDevice(device)) {
            is RuntimeSettingsUpdateResult.Success -> Unit
            is RuntimeSettingsUpdateResult.Forbidden -> {
                throw IllegalStateException("Register trusted device forbidden: ${result.reason}")
            }
            is RuntimeSettingsUpdateResult.ValidationFailed -> {
                throw IllegalArgumentException(
                    "Register trusted device failed validation: ${result.errors.joinToString()}"
                )
            }
        }
    }

    override suspend fun revokeTrustedDevice(deviceId: String) {
        ensureSanityCheck()
        when (val result = runtimeSettingsRepository.removeTrustedDevice(deviceId)) {
            is RuntimeSettingsUpdateResult.Success -> Unit
            is RuntimeSettingsUpdateResult.Forbidden -> {
                throw IllegalStateException("Revoke trusted device forbidden: ${result.reason}")
            }
            is RuntimeSettingsUpdateResult.ValidationFailed -> {
                throw IllegalArgumentException(
                    "Revoke trusted device failed validation: ${result.errors.joinToString()}"
                )
            }
        }
    }

    override suspend fun authorizeRequest(
        tokenCandidate: String?,
        deviceId: String?
    ): SecurityAuthDecision {
        ensureSanityCheck()

        val token = tokenCandidate?.trim().orEmpty()
        if (token.isBlank()) {
            return denied(STATUS_UNAUTHORIZED, "missing_token")
        }

        val tokenHash = hashToken(token)
        val tokenRecord = tokenStore.readTokens().firstOrNull { it.isActive && it.tokenHash == tokenHash }
            ?: return denied(STATUS_UNAUTHORIZED, "invalid_token")

        val settings = runtimeSettingsRepository.settings.first()
        if (settings.featureFlags.trustedDevices) {
            val trusted = settings.security.trustedDevices
            if (trusted.isNotEmpty()) {
                val candidateId = deviceId?.trim().orEmpty()
                if (candidateId.isBlank()) {
                    return denied(STATUS_FORBIDDEN, "device_id_required")
                }
                if (trusted.none { it.deviceId == candidateId }) {
                    return denied(STATUS_FORBIDDEN, "device_not_trusted")
                }
            }
        }

        val boundDeviceId = tokenRecord.boundDeviceId
        if (!boundDeviceId.isNullOrBlank()) {
            val candidateId = deviceId?.trim().orEmpty()
            if (candidateId.isBlank()) {
                return denied(STATUS_FORBIDDEN, "token_device_id_required")
            }
            if (candidateId != boundDeviceId) {
                return denied(STATUS_FORBIDDEN, "token_device_mismatch")
            }
        }

        return SecurityAuthDecision(
            allowed = true,
            statusCode = STATUS_OK,
            reason = "ok",
            tokenId = tokenRecord.tokenId,
            deviceId = deviceId?.trim()?.ifBlank { null }
        )
    }

    override suspend fun requestPairing(
        deviceId: String,
        displayName: String,
        clientType: PairingClientType
    ): PairingRequestStartResult {
        ensureSanityCheck()

        val normalizedDeviceId = deviceId.trim()
        val normalizedName = displayName.trim()
        if (!DEVICE_ID_REGEX.matches(normalizedDeviceId)) {
            return PairingRequestStartResult.Failure(STATUS_BAD_REQUEST, "invalid_device_id")
        }
        if (normalizedName.isBlank()) {
            return PairingRequestStartResult.Failure(STATUS_BAD_REQUEST, "invalid_display_name")
        }

        val now = System.currentTimeMillis()
        return pairingMutex.withLock {
            purgeExpiredPairingState(now)

            val requestId = randomId(REQUEST_ID_BYTES)
            val request = PendingPairingRequest(
                requestId = requestId,
                deviceId = normalizedDeviceId,
                displayName = normalizedName,
                clientType = clientType,
                verificationCode = randomDigits(PAIRING_CODE_DIGITS),
                createdAtEpochMs = now,
                expiresAtEpochMs = now + PAIRING_TTL_MS
            )

            pendingRequestsById.entries.removeIf { (_, existing) -> existing.deviceId == normalizedDeviceId }
            pendingRequestsById[requestId] = request
            publishPendingPairingRequestsLocked()

            logger.i(
                LogEventId.SECURITY_PAIRING_CHALLENGE_CREATED,
                "Pending pairing request created requestId=$requestId device=$normalizedDeviceId type=${clientType.name}"
            )

            PairingRequestStartResult.Success(
                requestId = request.requestId,
                deviceId = request.deviceId,
                displayName = request.displayName,
                expiresAtEpochMs = request.expiresAtEpochMs
            )
        }
    }

    override suspend fun completePairingRequest(
        requestId: String,
        verificationCode: String
    ): PairingResult {
        ensureSanityCheck()

        val normalizedRequestId = requestId.trim()
        val normalizedCode = verificationCode.trim()
        if (normalizedRequestId.isBlank()) {
            return PairingResult.Failure(STATUS_BAD_REQUEST, "pairing_request_id_required")
        }
        if (!PAIRING_CODE_REGEX.matches(normalizedCode)) {
            return PairingResult.Failure(STATUS_BAD_REQUEST, "invalid_pairing_code")
        }

        val now = System.currentTimeMillis()
        val completion = pairingMutex.withLock {
            purgeExpiredPairingState(now)
            val pending = pendingRequestsById[normalizedRequestId]
                ?: return@withLock PendingPairingCompletion.NotFound
            if (pending.verificationCode != normalizedCode) {
                return@withLock PendingPairingCompletion.InvalidCode
            }
            pendingRequestsById.remove(normalizedRequestId)
            publishPendingPairingRequestsLocked()
            PendingPairingCompletion.Ready(pending)
        }

        val request = when (completion) {
            PendingPairingCompletion.NotFound -> {
                return PairingResult.Failure(STATUS_NOT_FOUND, "pairing_request_not_found")
            }
            PendingPairingCompletion.InvalidCode -> {
                return PairingResult.Failure(STATUS_UNAUTHORIZED, "invalid_pairing_code")
            }
            is PendingPairingCompletion.Ready -> completion.request
        }

        val trustedDevice = TrustedDevice(
            deviceId = request.deviceId,
            displayName = request.displayName,
            addedAtEpochMillis = now
        )
        registerTrustedDevice(trustedDevice)

        val tokenIssue = tokenMutex.withLock {
            issueTokenLocked(boundDeviceId = trustedDevice.deviceId)
        }

        logger.i(
            LogEventId.SECURITY_PAIRING_COMPLETED,
            "Pending pairing completed requestId=${request.requestId} device=${trustedDevice.deviceId}"
        )
        return PairingResult.Success(
            tokenId = tokenIssue.tokenId,
            tokenValue = tokenIssue.tokenValue,
            deviceId = trustedDevice.deviceId
        )
    }

    override suspend fun cancelPairingRequest(requestId: String): PairingActionResult {
        ensureSanityCheck()

        val normalizedRequestId = requestId.trim()
        if (normalizedRequestId.isBlank()) {
            return PairingActionResult.Failure(STATUS_BAD_REQUEST, "pairing_request_id_required")
        }

        return pairingMutex.withLock {
            purgeExpiredPairingState(System.currentTimeMillis())
            val removed = pendingRequestsById.remove(normalizedRequestId)
                ?: return@withLock PairingActionResult.Failure(STATUS_NOT_FOUND, "pairing_request_not_found")
            publishPendingPairingRequestsLocked()
            logger.i(
                LogEventId.SECURITY_PAIRING_REPLAY_BLOCKED,
                "Pending pairing request canceled requestId=${removed.requestId} device=${removed.deviceId}"
            )
            PairingActionResult.Success("pairing request canceled")
        }
    }

    override suspend fun createPairingChallenge(): PairingChallenge {
        ensureSanityCheck()
        val now = System.currentTimeMillis()
        return pairingMutex.withLock {
            purgeExpiredPairingState(now)
            val challenge = PairingChallenge(
                sessionId = randomId(SESSION_ID_BYTES),
                pairingCode = randomCode(PAIRING_CODE_BYTES),
                createdAtEpochMs = now,
                expiresAtEpochMs = now + PAIRING_TTL_MS
            )
            pairingSessions[challenge.sessionId] = PairingSession(
                sessionId = challenge.sessionId,
                pairingCode = challenge.pairingCode,
                createdAtEpochMs = challenge.createdAtEpochMs,
                expiresAtEpochMs = challenge.expiresAtEpochMs
            )
            logger.i(LogEventId.SECURITY_PAIRING_CHALLENGE_CREATED, "Pairing challenge created sid=${challenge.sessionId}")
            challenge
        }
    }

    override suspend fun pairDevice(
        pin: String,
        sessionId: String,
        pairingCode: String,
        deviceId: String,
        displayName: String
    ): PairingResult {
        ensureSanityCheck()

        val normalizedDeviceId = deviceId.trim()
        val normalizedName = displayName.trim()
        if (!DEVICE_ID_REGEX.matches(normalizedDeviceId)) {
            return PairingResult.Failure(STATUS_BAD_REQUEST, "invalid_device_id")
        }
        if (normalizedName.isBlank()) {
            return PairingResult.Failure(STATUS_BAD_REQUEST, "invalid_display_name")
        }
        if (!validatePin(pin)) {
            return PairingResult.Failure(STATUS_UNAUTHORIZED, "invalid_pin")
        }

        val now = System.currentTimeMillis()
        val challengeStatus = pairingMutex.withLock {
            purgeExpiredPairingState(now)
            val session = pairingSessions[sessionId]
                ?: return@withLock PairingChallengeStatus.NOT_FOUND
            if (session.consumed) {
                return@withLock PairingChallengeStatus.REPLAY
            }
            if (session.pairingCode != pairingCode) {
                return@withLock PairingChallengeStatus.INVALID_CODE
            }
            session.copy(consumed = true).also { updated ->
                pairingSessions[sessionId] = updated
            }
            PairingChallengeStatus.OK
        }

        when (challengeStatus) {
            PairingChallengeStatus.NOT_FOUND -> {
                return PairingResult.Failure(STATUS_NOT_FOUND, "pairing_session_not_found")
            }
            PairingChallengeStatus.INVALID_CODE -> {
                return PairingResult.Failure(STATUS_UNAUTHORIZED, "invalid_pairing_code")
            }
            PairingChallengeStatus.REPLAY -> {
                logger.w(LogEventId.SECURITY_PAIRING_REPLAY_BLOCKED, "Pairing replay blocked sid=$sessionId")
                return PairingResult.Failure(STATUS_CONFLICT, "pairing_replay_detected")
            }
            PairingChallengeStatus.OK -> Unit
        }

        val trustedDevice = TrustedDevice(
            deviceId = normalizedDeviceId,
            displayName = normalizedName,
            addedAtEpochMillis = now
        )
        registerTrustedDevice(trustedDevice)

        val tokenIssue = tokenMutex.withLock {
            issueTokenLocked(boundDeviceId = normalizedDeviceId)
        }

        logger.i(
            LogEventId.SECURITY_PAIRING_COMPLETED,
            "Pairing completed sid=$sessionId device=${trustedDevice.deviceId}"
        )
        return PairingResult.Success(
            tokenId = tokenIssue.tokenId,
            tokenValue = tokenIssue.tokenValue,
            deviceId = trustedDevice.deviceId
        )
    }

    override suspend fun rotateToken(
        requesterToken: String?,
        requesterDeviceId: String?,
        revokeCurrent: Boolean
    ): TokenRotationResult {
        ensureSanityCheck()
        val auth = authorizeRequest(requesterToken, requesterDeviceId)
        if (!auth.allowed) {
            return TokenRotationResult.Failure(auth.statusCode, auth.reason)
        }

        val requesterHash = hashToken(requesterToken.orEmpty())
        val rotated = tokenMutex.withLock {
            val tokens = tokenStore.readTokens().toMutableList()
            val current = tokens.firstOrNull { it.isActive && it.tokenHash == requesterHash }

            val newToken = issueTokenLocked(
                existingTokens = tokens,
                boundDeviceId = current?.boundDeviceId ?: requesterDeviceId?.trim()?.ifBlank { null }
            )

            if (revokeCurrent && current != null) {
                val now = System.currentTimeMillis()
                val updatedTokens = tokens.map { token ->
                    if (token.tokenId == current.tokenId) token.copy(revokedAtEpochMs = now) else token
                }
                tokenStore.writeTokens(updatedTokens)
            }
            newToken
        }

        logger.i(LogEventId.SECURITY_TOKEN_ROTATED, "Token rotated tokenId=${rotated.tokenId}")
        return TokenRotationResult.Success(
            tokenId = rotated.tokenId,
            tokenValue = rotated.tokenValue
        )
    }

    override suspend fun revokeToken(
        requesterToken: String?,
        requesterDeviceId: String?,
        tokenIdToRevoke: String
    ): TokenRevocationResult {
        ensureSanityCheck()
        val auth = authorizeRequest(requesterToken, requesterDeviceId)
        if (!auth.allowed) {
            return TokenRevocationResult.Failure(auth.statusCode, auth.reason)
        }

        if (tokenIdToRevoke.isBlank()) {
            return TokenRevocationResult.Failure(STATUS_BAD_REQUEST, "token_id_required")
        }

        return tokenMutex.withLock {
            val tokens = tokenStore.readTokens().toMutableList()
            val target = tokens.firstOrNull { it.tokenId == tokenIdToRevoke }
                ?: return@withLock TokenRevocationResult.Failure(STATUS_NOT_FOUND, "token_not_found")
            if (!target.isActive) {
                return@withLock TokenRevocationResult.Failure(STATUS_CONFLICT, "token_already_revoked")
            }
            val activeCount = tokens.count { it.isActive }
            if (activeCount <= 1) {
                return@withLock TokenRevocationResult.Failure(STATUS_CONFLICT, "cannot_revoke_last_token")
            }

            val now = System.currentTimeMillis()
            val updated = tokens.map { token ->
                if (token.tokenId == target.tokenId) token.copy(revokedAtEpochMs = now) else token
            }
            tokenStore.writeTokens(updated)
            logger.i(LogEventId.SECURITY_TOKEN_REVOKED, "Token revoked tokenId=${target.tokenId}")
            TokenRevocationResult.Success(revokedTokenId = target.tokenId)
        }
    }

    override suspend fun sanityCheckAfterRestart() {
        ensureSanityCheck(force = true)
    }

    private suspend fun ensureSanityCheck(force: Boolean = false) {
        sanityMutex.withLock {
            if (sanityChecked && !force) return
            var repaired = false

            val currentSettings = runtimeSettingsRepository.settings.first()
            var repairedSettings = currentSettings

            val currentPin = currentSettings.security.pinCode
            if (!PIN_REGEX.matches(currentPin)) {
                repairedSettings = repairedSettings.copy(
                    security = repairedSettings.security.copy(pinCode = RuntimeSettingsDefaults.value.security.pinCode)
                )
                repaired = true
            }
            val currentToken = currentSettings.security.apiToken
            if (!TOKEN_REGEX.matches(currentToken)) {
                repairedSettings = repairedSettings.copy(
                    security = repairedSettings.security.copy(apiToken = RuntimeSettingsDefaults.value.security.apiToken)
                )
                repaired = true
            }

            if (repairedSettings != currentSettings) {
                when (runtimeSettingsRepository.updateSettings(repairedSettings)) {
                    is RuntimeSettingsUpdateResult.Success -> Unit
                    else -> {
                        logger.w(
                            LogEventId.SECURITY_SANITY_REPAIRED,
                            "Security settings required repair but persistence failed, continuing with runtime values"
                        )
                    }
                }
            }

            tokenMutex.withLock {
                val sanitized = tokenStore.readTokens()
                    .filter { it.tokenId.isNotBlank() && it.tokenHash.isNotBlank() && it.issuedAtEpochMs > 0L }
                    .distinctBy { it.tokenId }
                    .toMutableList()
                if (sanitized.none { it.isActive }) {
                    val bootstrapToken = repairedSettings.security.apiToken
                    val now = System.currentTimeMillis()
                    sanitized += StoredApiToken(
                        tokenId = randomId(TOKEN_ID_BYTES),
                        tokenHash = hashToken(bootstrapToken),
                        boundDeviceId = null,
                        issuedAtEpochMs = now
                    )
                    repaired = true
                }
                tokenStore.writeTokens(sanitized)
            }

            sanityChecked = true
            if (repaired) {
                logger.i(LogEventId.SECURITY_SANITY_REPAIRED, "Security sanity check repaired local auth state")
            } else {
                logger.i(LogEventId.SECURITY_SANITY_OK, "Security sanity check passed")
            }
        }
    }

    private suspend fun issueTokenLocked(
        boundDeviceId: String?,
        existingTokens: MutableList<StoredApiToken>? = null
    ): IssuedToken {
        val tokens = existingTokens ?: tokenStore.readTokens().toMutableList()
        val now = System.currentTimeMillis()
        var tokenId = randomId(TOKEN_ID_BYTES)
        while (tokens.any { it.tokenId == tokenId }) {
            tokenId = randomId(TOKEN_ID_BYTES)
        }
        val tokenSecret = randomCode(TOKEN_SECRET_BYTES)
        val tokenValue = "zcam_${tokenId}_$tokenSecret"
        val token = StoredApiToken(
            tokenId = tokenId,
            tokenHash = hashToken(tokenValue),
            boundDeviceId = boundDeviceId,
            issuedAtEpochMs = now
        )
        tokens += token
        tokenStore.writeTokens(tokens)
        return IssuedToken(tokenId = tokenId, tokenValue = tokenValue)
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray(Charsets.UTF_8))
        return hash.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun randomId(bytes: Int): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw).lowercase()
    }

    private fun randomCode(bytes: Int): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }

    private fun randomDigits(length: Int): String {
        return buildString(length) {
            repeat(length) {
                append(random.nextInt(10))
            }
        }
    }

    private fun purgeExpiredPairingState(now: Long) {
        var pendingRequestsChanged = false

        val legacyIterator = pairingSessions.iterator()
        while (legacyIterator.hasNext()) {
            val (_, session) = legacyIterator.next()
            if (session.expiresAtEpochMs <= now) {
                legacyIterator.remove()
            }
        }

        val pendingIterator = pendingRequestsById.iterator()
        while (pendingIterator.hasNext()) {
            val (_, request) = pendingIterator.next()
            if (request.expiresAtEpochMs <= now) {
                pendingIterator.remove()
                pendingRequestsChanged = true
            }
        }

        if (pendingRequestsChanged) {
            publishPendingPairingRequestsLocked()
        }
    }

    private fun publishPendingPairingRequestsLocked() {
        pendingPairingRequestsState.value = pendingRequestsById.values
            .sortedByDescending(PendingPairingRequest::createdAtEpochMs)
    }

    private fun denied(statusCode: Int, reason: String): SecurityAuthDecision {
        logger.w(LogEventId.SECURITY_AUTH_REJECTED, "Auth rejected: $reason")
        return SecurityAuthDecision(
            allowed = false,
            statusCode = statusCode,
            reason = reason
        )
    }

    private data class PairingSession(
        val sessionId: String,
        val pairingCode: String,
        val createdAtEpochMs: Long,
        val expiresAtEpochMs: Long,
        val consumed: Boolean = false
    )

    private data class IssuedToken(
        val tokenId: String,
        val tokenValue: String
    )

    private enum class PairingChallengeStatus {
        OK,
        NOT_FOUND,
        INVALID_CODE,
        REPLAY
    }

    private sealed interface PendingPairingCompletion {
        data object NotFound : PendingPairingCompletion
        data object InvalidCode : PendingPairingCompletion
        data class Ready(val request: PendingPairingRequest) : PendingPairingCompletion
    }

    private companion object {
        val PIN_REGEX = Regex("^[0-9]{4,10}$")
        val TOKEN_REGEX = Regex("^[A-Za-z0-9_-]{8,128}$")
        val DEVICE_ID_REGEX = Regex("^[A-Za-z0-9._:-]{3,64}$")
        val PAIRING_CODE_REGEX = Regex("^[0-9]{6}$")

        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_UNAUTHORIZED = 401
        const val STATUS_FORBIDDEN = 403
        const val STATUS_NOT_FOUND = 404
        const val STATUS_CONFLICT = 409

        const val PAIRING_TTL_MS = 120_000L
        const val PAIRING_CODE_DIGITS = 6
        const val REQUEST_ID_BYTES = 9
        const val SESSION_ID_BYTES = 9
        const val PAIRING_CODE_BYTES = 18
        const val TOKEN_ID_BYTES = 9
        const val TOKEN_SECRET_BYTES = 24
    }
}
