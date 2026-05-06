package com.zcam.core.domain.config

data class VideoResolution(
    val width: Int,
    val height: Int
) {
    init {
        require(width > 0) { "Resolution width must be positive." }
        require(height > 0) { "Resolution height must be positive." }
    }

    companion object {
        val HD_720P = VideoResolution(width = 1280, height = 720)
    }
}
