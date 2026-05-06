package com.zcam.data

import com.zcam.core.domain.config.TrustedDevice
import java.util.Base64

internal object TrustedDeviceCodec {

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    fun encode(device: TrustedDevice): String {
        val id = encoder.encodeToString(device.deviceId.toByteArray(Charsets.UTF_8))
        val name = encoder.encodeToString(device.displayName.toByteArray(Charsets.UTF_8))
        return "$id|$name|${device.addedAtEpochMillis}"
    }

    fun decode(raw: String): TrustedDevice? {
        val parts = raw.split('|', limit = 3)
        if (parts.size != 3) return null

        return runCatching {
            TrustedDevice(
                deviceId = decoder.decode(parts[0]).toString(Charsets.UTF_8),
                displayName = decoder.decode(parts[1]).toString(Charsets.UTF_8),
                addedAtEpochMillis = parts[2].toLong()
            )
        }.getOrNull()
    }
}
