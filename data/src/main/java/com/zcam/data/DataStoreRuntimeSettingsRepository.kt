package com.zcam.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zcam.core.dispatchers.IoDispatcher
import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.FeatureFlags
import com.zcam.core.domain.config.LoopRecordingConfig
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.RuntimeSettingsValidator
import com.zcam.core.domain.config.SecurityConfig
import com.zcam.core.domain.config.StreamConfig
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.config.VideoCodec
import com.zcam.core.domain.config.VideoResolution
import com.zcam.core.domain.settings.FeatureFlagGuard
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.runtimeSettingsDataStore by preferencesDataStore(name = "zcam_runtime_settings")

@Singleton
class DataStoreRuntimeSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val featureFlagGuard: FeatureFlagGuard
) : RuntimeSettingsRepository {

    override val settings: Flow<RuntimeSettings> = context.runtimeSettingsDataStore.data
        .map { prefs -> prefs.toRuntimeSettings() }
        .flowOn(ioDispatcher)

    override suspend fun updateSettings(candidate: RuntimeSettings): RuntimeSettingsUpdateResult = withContext(ioDispatcher) {
        val current = context.runtimeSettingsDataStore.data
            .map { it.toRuntimeSettings() }
            .firstOrDefault(RuntimeSettingsDefaults.value)

        val forbiddenFlag = FeatureFlag.entries.firstOrNull { flag ->
            !featureFlagGuard.canUpdate(flag) &&
                current.featureFlags.isEnabled(flag) != candidate.featureFlags.isEnabled(flag)
        }
        if (forbiddenFlag != null) {
            return@withContext RuntimeSettingsUpdateResult.Forbidden(
                reason = "Feature flag $forbiddenFlag cannot be changed via runtime settings update."
            )
        }

        val errors = RuntimeSettingsValidator.validate(candidate)
        if (errors.isNotEmpty()) {
            return@withContext RuntimeSettingsUpdateResult.ValidationFailed(errors)
        }

        context.runtimeSettingsDataStore.edit { prefs ->
            prefs.writeRuntimeSettings(candidate)
        }

        RuntimeSettingsUpdateResult.Success(candidate)
    }

    override suspend fun setFeatureFlag(flag: FeatureFlag, enabled: Boolean): RuntimeSettingsUpdateResult = withContext(ioDispatcher) {
        if (!featureFlagGuard.canUpdate(flag)) {
            return@withContext RuntimeSettingsUpdateResult.Forbidden("Feature flag $flag cannot be changed at runtime.")
        }

        val current = context.runtimeSettingsDataStore.data.map { it.toRuntimeSettings() }
            .firstOrDefault(RuntimeSettingsDefaults.value)

        val updated = current.copy(featureFlags = current.featureFlags.withFlag(flag, enabled))
        updateSettings(updated)
    }

    override suspend fun upsertTrustedDevice(device: TrustedDevice): RuntimeSettingsUpdateResult = withContext(ioDispatcher) {
        val current = context.runtimeSettingsDataStore.data.map { it.toRuntimeSettings() }
            .firstOrDefault(RuntimeSettingsDefaults.value)

        val nextDevices = current.security.trustedDevices
            .filterNot { it.deviceId == device.deviceId }
            .toSet() + device

        val updated = current.copy(security = current.security.copy(trustedDevices = nextDevices))
        updateSettings(updated)
    }

    override suspend fun removeTrustedDevice(deviceId: String): RuntimeSettingsUpdateResult = withContext(ioDispatcher) {
        val current = context.runtimeSettingsDataStore.data.map { it.toRuntimeSettings() }
            .firstOrDefault(RuntimeSettingsDefaults.value)

        val updated = current.copy(
            security = current.security.copy(
                trustedDevices = current.security.trustedDevices.filterNot { it.deviceId == deviceId }.toSet()
            )
        )
        updateSettings(updated)
    }

    private fun Preferences.toRuntimeSettings(): RuntimeSettings {
        val defaults = RuntimeSettingsDefaults.value
        val storedWidth = this[RESOLUTION_WIDTH] ?: defaults.stream.resolution.width
        val storedHeight = this[RESOLUTION_HEIGHT] ?: defaults.stream.resolution.height
        val safeWidth = if (storedWidth > 0) storedWidth else defaults.stream.resolution.width
        val safeHeight = if (storedHeight > 0) storedHeight else defaults.stream.resolution.height

        val stream = StreamConfig(
            resolution = VideoResolution(
                width = safeWidth,
                height = safeHeight
            ),
            fps = this[FPS] ?: defaults.stream.fps,
            codec = this[VIDEO_CODEC]
                ?.let { raw -> VideoCodec.entries.firstOrNull { it.name == raw } }
                ?: defaults.stream.codec
        )

        val recording = LoopRecordingConfig(
            segmentMinutes = this[SEGMENT_MINUTES] ?: defaults.recording.segmentMinutes,
            maxStorageGb = this[MAX_STORAGE_GB] ?: defaults.recording.maxStorageGb,
            minFreeStorageGb = this[MIN_FREE_STORAGE_GB] ?: defaults.recording.minFreeStorageGb
        )

        val security = SecurityConfig(
            pinCode = this[PIN] ?: defaults.security.pinCode,
            apiToken = this[TOKEN] ?: defaults.security.apiToken,
            trustedDevices = (this[TRUSTED_DEVICES] ?: emptySet())
                .mapNotNull { TrustedDeviceCodec.decode(it) }
                .toSet()
        )

        val featureFlags = FeatureFlags(
            mjpegStreaming = this[FLAG_MJPEG_STREAMING] ?: defaults.featureFlags.mjpegStreaming,
            loopRecording = this[FLAG_LOOP_RECORDING] ?: defaults.featureFlags.loopRecording,
            audioPushToTalk = this[FLAG_AUDIO_PUSH_TO_TALK] ?: defaults.featureFlags.audioPushToTalk,
            audioLive = this[FLAG_AUDIO_LIVE] ?: defaults.featureFlags.audioLive,
            audioPlayback = this[FLAG_AUDIO_PLAYBACK] ?: defaults.featureFlags.audioPlayback,
            trustedDevices = this[FLAG_TRUSTED_DEVICES] ?: defaults.featureFlags.trustedDevices,
            watchdogRecovery = this[FLAG_WATCHDOG_RECOVERY] ?: defaults.featureFlags.watchdogRecovery
        )

        val runtime = RuntimeSettings(
            serverPort = this[PORT] ?: defaults.serverPort,
            stream = stream,
            recording = recording,
            security = security,
            featureFlags = featureFlags
        )

        val errors = RuntimeSettingsValidator.validate(runtime)
        return if (errors.isEmpty()) runtime else defaults
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.writeRuntimeSettings(settings: RuntimeSettings) {
        this[PORT] = settings.serverPort
        this[RESOLUTION_WIDTH] = settings.stream.resolution.width
        this[RESOLUTION_HEIGHT] = settings.stream.resolution.height
        this[FPS] = settings.stream.fps
        this[VIDEO_CODEC] = settings.stream.codec.name

        this[SEGMENT_MINUTES] = settings.recording.segmentMinutes
        this[MAX_STORAGE_GB] = settings.recording.maxStorageGb
        this[MIN_FREE_STORAGE_GB] = settings.recording.minFreeStorageGb

        this[PIN] = settings.security.pinCode
        this[TOKEN] = settings.security.apiToken
        this[TRUSTED_DEVICES] = settings.security.trustedDevices.mapTo(mutableSetOf()) { TrustedDeviceCodec.encode(it) }

        this[FLAG_MJPEG_STREAMING] = settings.featureFlags.mjpegStreaming
        this[FLAG_LOOP_RECORDING] = settings.featureFlags.loopRecording
        this[FLAG_AUDIO_PUSH_TO_TALK] = settings.featureFlags.audioPushToTalk
        this[FLAG_AUDIO_LIVE] = settings.featureFlags.audioLive
        this[FLAG_AUDIO_PLAYBACK] = settings.featureFlags.audioPlayback
        this[FLAG_TRUSTED_DEVICES] = settings.featureFlags.trustedDevices
        this[FLAG_WATCHDOG_RECOVERY] = settings.featureFlags.watchdogRecovery
    }

    private companion object {
        val PORT = intPreferencesKey("server_port")
        val RESOLUTION_WIDTH = intPreferencesKey("stream_resolution_width")
        val RESOLUTION_HEIGHT = intPreferencesKey("stream_resolution_height")
        val FPS = intPreferencesKey("stream_fps")
        val VIDEO_CODEC = stringPreferencesKey("stream_codec")

        val SEGMENT_MINUTES = intPreferencesKey("recording_segment_minutes")
        val MAX_STORAGE_GB = intPreferencesKey("recording_max_storage_gb")
        val MIN_FREE_STORAGE_GB = intPreferencesKey("recording_min_free_storage_gb")

        val PIN = stringPreferencesKey("security_pin_code")
        val TOKEN = stringPreferencesKey("security_api_token")
        val TRUSTED_DEVICES = stringSetPreferencesKey("security_trusted_devices")

        val FLAG_MJPEG_STREAMING = booleanPreferencesKey("flags_mjpeg_streaming")
        val FLAG_LOOP_RECORDING = booleanPreferencesKey("flags_loop_recording")
        val FLAG_AUDIO_PUSH_TO_TALK = booleanPreferencesKey("flags_audio_push_to_talk")
        val FLAG_AUDIO_LIVE = booleanPreferencesKey("flags_audio_live")
        val FLAG_AUDIO_PLAYBACK = booleanPreferencesKey("flags_audio_playback")
        val FLAG_TRUSTED_DEVICES = booleanPreferencesKey("flags_trusted_devices")
        val FLAG_WATCHDOG_RECOVERY = booleanPreferencesKey("flags_watchdog_recovery")
    }
}

private suspend fun <T> Flow<T>.firstOrDefault(default: T): T {
    return firstOrNull() ?: default
}
