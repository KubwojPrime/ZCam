package com.zcam.service.runtime

data class RecoveryPolicy(
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 30_000L,
    val maxAttemptsBeforeCooldown: Int = 5,
    val cooldownMs: Long = 120_000L
) {
    init {
        require(baseDelayMs > 0) { "baseDelayMs must be positive" }
        require(maxDelayMs >= baseDelayMs) { "maxDelayMs must be >= baseDelayMs" }
        require(maxAttemptsBeforeCooldown > 0) { "maxAttemptsBeforeCooldown must be positive" }
        require(cooldownMs > 0) { "cooldownMs must be positive" }
    }

    fun nextDelayMs(attempt: Int): Long {
        val normalizedAttempt = attempt.coerceAtLeast(1)
        val exponent = (normalizedAttempt - 1).coerceAtMost(30)
        val expDelay = baseDelayMs * (1L shl exponent)
        return expDelay.coerceAtMost(maxDelayMs)
    }
}
