package com.zcam.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanAccessPolicy @Inject constructor() {

    fun isLanClient(ipAddress: String?): Boolean {
        if (ipAddress.isNullOrBlank()) return false
        return runCatching {
            val address = InetAddress.getByName(ipAddress)
            when (address) {
                is Inet4Address -> isAllowedIpv4(address.address)
                is Inet6Address -> isAllowedIpv6(address.address)
                else -> false
            }
        }.getOrElse { false }
    }

    private fun isAllowedIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return false
        val b0 = bytes[0].toUByte().toInt()
        val b1 = bytes[1].toUByte().toInt()

        if (b0 == 127) return true // loopback
        if (b0 == 10) return true // RFC1918
        if (b0 == 192 && b1 == 168) return true // RFC1918
        if (b0 == 172 && b1 in 16..31) return true // RFC1918
        if (b0 == 169 && b1 == 254) return true // link-local
        if (b0 == 100 && b1 in 64..127) return true // CGNAT, common for VPN overlays

        return false
    }

    private fun isAllowedIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return false

        val first = bytes[0].toUByte().toInt()
        val second = bytes[1].toUByte().toInt()
        val isLoopback = bytes.dropLast(1).all { it == 0.toByte() } && bytes.last() == 1.toByte()
        if (isLoopback) return true // ::1

        val isUniqueLocal = (first and 0xFE) == 0xFC // fc00::/7
        if (isUniqueLocal) return true

        val isLinkLocal = first == 0xFE && (second and 0xC0) == 0x80 // fe80::/10
        if (isLinkLocal) return true

        return false
    }
}
