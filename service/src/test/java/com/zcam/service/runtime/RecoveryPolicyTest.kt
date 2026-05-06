package com.zcam.service.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryPolicyTest {

    @Test
    fun delay_grows_exponentially_and_caps_at_max() {
        val policy = RecoveryPolicy(baseDelayMs = 100, maxDelayMs = 1_000, maxAttemptsBeforeCooldown = 5, cooldownMs = 10_000)

        assertEquals(100, policy.nextDelayMs(1))
        assertEquals(200, policy.nextDelayMs(2))
        assertEquals(400, policy.nextDelayMs(3))
        assertEquals(800, policy.nextDelayMs(4))
        assertEquals(1_000, policy.nextDelayMs(5))
        assertEquals(1_000, policy.nextDelayMs(10))
    }
}
