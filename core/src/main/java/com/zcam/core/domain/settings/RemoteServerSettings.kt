package com.zcam.core.domain.settings

import com.zcam.core.domain.config.*
import java.security.MessageDigest

data class RemoteServerSettings(val settings: RuntimeSettings, val revision: String)

/** Flat wire contract for operational settings. Credentials and trust policy stay on the server. */
object RemoteServerSettingsCodec {
    fun encode(settings: RuntimeSettings): Map<String, String> = linkedMapOf(
        "serverPort" to settings.serverPort.toString(),
        "streamWidth" to settings.stream.resolution.width.toString(),
        "streamHeight" to settings.stream.resolution.height.toString(),
        "streamFps" to settings.stream.fps.toString(),
        "rearLens" to settings.stream.rearLens.wireName,
        "eventSensitivity" to settings.stream.eventSensitivity.wireName,
        "previewTransport" to settings.stream.preview.transport.wireName,
        "previewWidth" to settings.stream.preview.resolution.width.toString(),
        "previewHeight" to settings.stream.preview.resolution.height.toString(),
        "previewFps" to settings.stream.preview.fps.toString(),
        "previewBitrateKbps" to settings.stream.preview.bitrateKbps.toString(),
        "segmentMinutes" to settings.recording.segmentMinutes.toString(),
        "maxStorageGb" to settings.recording.maxStorageGb.toString(),
        "minFreeStorageGb" to settings.recording.minFreeStorageGb.toString(),
        "mjpegStreaming" to settings.featureFlags.mjpegStreaming.toString(),
        "loopRecording" to settings.featureFlags.loopRecording.toString(),
        "audioPushToTalk" to settings.featureFlags.audioPushToTalk.toString(),
        "audioLive" to settings.featureFlags.audioLive.toString(),
        "audioPlayback" to settings.featureFlags.audioPlayback.toString(),
        "watchdogRecovery" to settings.featureFlags.watchdogRecovery.toString()
    )

    fun revision(settings: RuntimeSettings): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(settings).entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun decode(values: Map<String, String>, base: RuntimeSettings = RuntimeSettingsDefaults.value): RuntimeSettings {
        require(values.keys == encode(base).keys) { "Unexpected or missing settings fields" }
        fun number(key: String): Int = values.getValue(key).toIntOrNull() ?: error("Invalid $key")
        fun flag(key: String): Boolean = values.getValue(key).toBooleanStrictOrNull() ?: error("Invalid $key")
        fun resolution(width: String, height: String): VideoResolution {
            val w = number(width)
            val h = number(height)
            require(w in 160..8192 && h in 120..8192 && w % 2 == 0 && h % 2 == 0) { "Invalid resolution" }
            return VideoResolution(w, h)
        }
        val lens = RearCameraLens.entries.firstOrNull { it.wireName == values["rearLens"] } ?: error("Invalid rearLens")
        val sensitivity = EventDetectionSensitivity.entries.firstOrNull { it.wireName == values["eventSensitivity"] }
            ?: error("Invalid eventSensitivity")
        val transport = PreviewTransport.entries.firstOrNull { it.wireName == values["previewTransport"] }
            ?: error("Invalid previewTransport")
        val candidate = base.copy(
            serverPort = number("serverPort"),
            stream = base.stream.copy(
                resolution = resolution("streamWidth", "streamHeight"), fps = number("streamFps"),
                rearLens = lens, eventSensitivity = sensitivity,
                preview = base.stream.preview.copy(
                    transport = transport, resolution = resolution("previewWidth", "previewHeight"),
                    fps = number("previewFps"), bitrateKbps = number("previewBitrateKbps")
                )
            ),
            recording = base.recording.copy(
                segmentMinutes = number("segmentMinutes"), maxStorageGb = number("maxStorageGb"),
                minFreeStorageGb = number("minFreeStorageGb")
            ),
            featureFlags = base.featureFlags.copy(
                mjpegStreaming = flag("mjpegStreaming"), loopRecording = flag("loopRecording"),
                audioPushToTalk = flag("audioPushToTalk"), audioLive = flag("audioLive"),
                audioPlayback = flag("audioPlayback"), watchdogRecovery = flag("watchdogRecovery")
            )
        )
        require(RuntimeSettingsValidator.validate(candidate).isEmpty()) { "Invalid settings values" }
        return candidate
    }
}
