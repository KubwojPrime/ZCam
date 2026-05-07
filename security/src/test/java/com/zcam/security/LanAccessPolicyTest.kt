package com.zcam.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanAccessPolicyTest {

    private val policy = LanAccessPolicy()

    @Test
    fun allows_lan_and_common_vpn_ranges() {
        assertTrue(policy.isLanClient("127.0.0.1"))
        assertTrue(policy.isLanClient("192.168.1.20"))
        assertTrue(policy.isLanClient("10.20.30.40"))
        assertTrue(policy.isLanClient("172.16.5.9"))
        assertTrue(policy.isLanClient("100.64.1.5"))
        assertTrue(policy.isLanClient("fc00::1"))
        assertTrue(policy.isLanClient("fe80::1"))
    }

    @Test
    fun rejects_public_ranges() {
        assertFalse(policy.isLanClient("8.8.8.8"))
        assertFalse(policy.isLanClient("1.1.1.1"))
        assertFalse(policy.isLanClient("2001:4860:4860::8888"))
        assertFalse(policy.isLanClient("2606:4700:4700::1111"))
        assertFalse(policy.isLanClient("not-an-ip"))
    }
}
