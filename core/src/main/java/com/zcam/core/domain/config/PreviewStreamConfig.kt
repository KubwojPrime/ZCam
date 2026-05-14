package com.zcam.core.domain.config

data class PreviewStreamConfig(
    val transport: PreviewTransport = PreviewTransport.H264,
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val fps: Int = 15,
    val bitrateKbps: Int = 1200
)
