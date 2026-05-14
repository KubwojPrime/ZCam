package com.zcam.core.domain.config

enum class PreviewProfile(
    val label: String,
    val resolution: VideoResolution,
    val fps: Int,
    val bitrateKbps: Int
) {
    LOW(
        label = "Low",
        resolution = VideoResolution(width = 854, height = 480),
        fps = 12,
        bitrateKbps = 700
    ),
    BALANCED(
        label = "Balanced",
        resolution = VideoResolution.HD_720P,
        fps = 15,
        bitrateKbps = 1200
    ),
    HIGH(
        label = "High",
        resolution = VideoResolution(width = 1920, height = 1080),
        fps = 15,
        bitrateKbps = 2500
    );

    fun toConfig(transport: PreviewTransport): PreviewStreamConfig {
        return PreviewStreamConfig(
            transport = transport,
            resolution = resolution,
            fps = fps,
            bitrateKbps = bitrateKbps
        )
    }

    companion object {
        fun match(config: PreviewStreamConfig): PreviewProfile? {
            return entries.firstOrNull { profile ->
                profile.resolution == config.resolution &&
                    profile.fps == config.fps &&
                    profile.bitrateKbps == config.bitrateKbps
            }
        }
    }
}
