package com.zcam.core.domain.config

data class StreamConfig(
    val resolution: VideoResolution = VideoResolution.HD_720P,
    val fps: Int = 15,
    val codec: VideoCodec = VideoCodec.H264,
    val rearLens: RearCameraLens = RearCameraLens.MAIN,
    val preview: PreviewStreamConfig = PreviewStreamConfig()
)
