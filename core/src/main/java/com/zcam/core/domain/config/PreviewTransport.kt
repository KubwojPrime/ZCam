package com.zcam.core.domain.config

enum class PreviewTransport(
    val wireName: String,
    val label: String
) {
    H264("h264", "H.264"),
    MJPEG("mjpeg", "MJPEG");

    companion object {
        fun fromWireName(value: String?): PreviewTransport {
            return entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }
                ?: H264
        }
    }
}
