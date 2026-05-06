package com.zcam.security

import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanAccessPolicy @Inject constructor() {

    fun isLanClient(ipAddress: String?): Boolean {
        if (ipAddress.isNullOrBlank()) return false
        return runCatching {
            val address = InetAddress.getByName(ipAddress)
            address.isSiteLocalAddress || address.isLoopbackAddress
        }.getOrElse { false }
    }
}
