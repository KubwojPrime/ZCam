package com.zcam.data

import com.zcam.core.domain.config.TrustedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TrustedDeviceCodecTest {

    @Test
    fun round_trip_preserves_device_values() {
        val input = TrustedDevice(
            deviceId = "device-123",
            displayName = "Kitchen Cam",
            addedAtEpochMillis = 1_700_000_000_000
        )

        val encoded = TrustedDeviceCodec.encode(input)
        val decoded = TrustedDeviceCodec.decode(encoded)

        assertNotNull(decoded)
        assertEquals(input, decoded)
    }

    @Test
    fun decode_returns_null_for_invalid_payload() {
        assertNull(TrustedDeviceCodec.decode("bad-payload"))
    }
}
