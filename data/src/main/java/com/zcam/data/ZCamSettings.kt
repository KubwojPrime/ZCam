package com.zcam.data

data class ZCamSettings(
    val serverPort: Int = 8080,
    val streamFps: Int = 15,
    val segmentMinutes: Int = 5,
    val maxStorageGb: Int = 32,
    val minFreeStorageGb: Int = 5,
    val pinCode: String = "0000",
    val apiToken: String = "local-token"
)
