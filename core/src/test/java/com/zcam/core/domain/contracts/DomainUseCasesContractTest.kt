package com.zcam.core.domain.contracts

import com.zcam.core.domain.audio.AudioEngine
import com.zcam.core.domain.audio.BeginPushToTalkUseCase
import com.zcam.core.domain.audio.EndPushToTalkUseCase
import com.zcam.core.domain.audio.PlaybackSource
import com.zcam.core.domain.audio.StartLiveAudioUseCase
import com.zcam.core.domain.audio.StartAudioPlaybackUseCase
import com.zcam.core.domain.audio.StopAudioPlaybackUseCase
import com.zcam.core.domain.audio.StopLiveAudioUseCase
import com.zcam.core.domain.config.LoopRecordingConfig
import com.zcam.core.domain.config.StreamConfig
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.mjpeg.MjpegStreamingEngine
import com.zcam.core.domain.mjpeg.StartMjpegStreamingUseCase
import com.zcam.core.domain.mjpeg.StopMjpegStreamingUseCase
import com.zcam.core.domain.recording.LoopRecordingEngine
import com.zcam.core.domain.recording.StartLoopRecordingUseCase
import com.zcam.core.domain.recording.StopLoopRecordingUseCase
import com.zcam.core.domain.security.AuthenticatePinUseCase
import com.zcam.core.domain.security.AuthenticateTokenUseCase
import com.zcam.core.domain.security.RegisterTrustedDeviceUseCase
import com.zcam.core.domain.security.SecurityEngine
import com.zcam.core.domain.watchdog.RecordHeartbeatUseCase
import com.zcam.core.domain.watchdog.RecoveryRequest
import com.zcam.core.domain.watchdog.RecoveryReason
import com.zcam.core.domain.watchdog.RequestRecoveryUseCase
import com.zcam.core.domain.watchdog.WatchdogComponentHealth
import com.zcam.core.domain.watchdog.WatchdogComponentStatus
import com.zcam.core.domain.watchdog.WatchdogHealthSnapshot
import com.zcam.core.domain.watchdog.WatchdogEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainUseCasesContractTest {

    @Test
    fun mjpeg_use_cases_delegate_to_engine() = runBlocking {
        val fake = FakeMjpegEngine()
        val config = StreamConfig()

        StartMjpegStreamingUseCase(fake).invoke(config)
        StopMjpegStreamingUseCase(fake).invoke()

        assertEquals(config, fake.startedWith)
        assertTrue(fake.stopCalled)
    }

    @Test
    fun loop_recording_use_cases_delegate_to_engine() = runBlocking {
        val fake = FakeLoopRecordingEngine()
        val config = LoopRecordingConfig(segmentMinutes = 7, maxStorageGb = 40, minFreeStorageGb = 6)

        StartLoopRecordingUseCase(fake).invoke(config)
        StopLoopRecordingUseCase(fake).invoke()

        assertEquals(config, fake.startedWith)
        assertTrue(fake.stopCalled)
    }

    @Test
    fun audio_use_cases_delegate_to_engine() = runBlocking {
        val fake = FakeAudioEngine()

        BeginPushToTalkUseCase(fake).invoke()
        EndPushToTalkUseCase(fake).invoke()
        StartLiveAudioUseCase(fake).invoke()
        StopLiveAudioUseCase(fake).invoke()
        StartAudioPlaybackUseCase(fake).invoke(PlaybackSource.ARCHIVE)
        StopAudioPlaybackUseCase(fake).invoke()

        assertTrue(fake.beginPttCalled)
        assertTrue(fake.endPttCalled)
        assertTrue(fake.startLiveCalled)
        assertTrue(fake.stopLiveCalled)
        assertEquals(PlaybackSource.ARCHIVE, fake.playbackSource)
        assertTrue(fake.stopPlaybackCalled)
    }

    @Test
    fun security_use_cases_delegate_to_engine() = runBlocking {
        val fake = FakeSecurityEngine(pinValid = true, tokenValid = false)
        val device = TrustedDevice("dev-1", "Front Door", 1_700_000_000_000)

        val pinResult = AuthenticatePinUseCase(fake).invoke("1234")
        val tokenResult = AuthenticateTokenUseCase(fake).invoke("token")
        RegisterTrustedDeviceUseCase(fake).invoke(device)

        assertTrue(pinResult)
        assertFalse(tokenResult)
        assertEquals(device, fake.lastRegisteredDevice)
    }

    @Test
    fun watchdog_use_cases_delegate_to_engine() = runBlocking {
        val fake = FakeWatchdogEngine()

        RecordHeartbeatUseCase(fake).invoke("camera")
        RequestRecoveryUseCase(fake).invoke("server", RecoveryReason.STALE_HEARTBEAT, "stale stream")

        assertEquals("camera", fake.lastHeartbeatComponent)
        assertEquals("server", fake.lastRecoveryRequest?.component)
        assertEquals(RecoveryReason.STALE_HEARTBEAT, fake.lastRecoveryRequest?.reason)
        assertEquals("stale stream", fake.lastRecoveryRequest?.details)
    }

    private class FakeMjpegEngine : MjpegStreamingEngine {
        var startedWith: StreamConfig? = null
        var stopCalled: Boolean = false

        override suspend fun start(config: StreamConfig) {
            startedWith = config
        }

        override suspend fun stop() {
            stopCalled = true
        }

        override fun latestFrame(): ByteArray = byteArrayOf(1)
    }

    private class FakeLoopRecordingEngine : LoopRecordingEngine {
        var startedWith: LoopRecordingConfig? = null
        var stopCalled: Boolean = false

        override suspend fun start(config: LoopRecordingConfig) {
            startedWith = config
        }

        override suspend fun stop() {
            stopCalled = true
        }

        override suspend fun forceRetentionSweep() {
            // no-op
        }
    }

    private class FakeAudioEngine : AudioEngine {
        var beginPttCalled: Boolean = false
        var endPttCalled: Boolean = false
        var startLiveCalled: Boolean = false
        var stopLiveCalled: Boolean = false
        var playbackSource: PlaybackSource? = null
        var stopPlaybackCalled: Boolean = false

        override suspend fun start() {
            // no-op
        }

        override suspend fun stop() {
            // no-op
        }

        override suspend fun beginPushToTalk() {
            beginPttCalled = true
        }

        override suspend fun endPushToTalk() {
            endPttCalled = true
        }

        override suspend fun beginLiveListen() {
            startLiveCalled = true
        }

        override suspend fun stopLiveListen() {
            stopLiveCalled = true
        }

        override suspend fun startPlayback(source: PlaybackSource) {
            playbackSource = source
        }

        override suspend fun stopPlayback() {
            stopPlaybackCalled = true
        }
    }

    private class FakeSecurityEngine(
        private val pinValid: Boolean,
        private val tokenValid: Boolean
    ) : SecurityEngine {
        var lastRegisteredDevice: TrustedDevice? = null

        override suspend fun validatePin(candidate: String): Boolean = pinValid

        override suspend fun validateToken(candidate: String): Boolean = tokenValid

        override suspend fun isTrustedDevice(deviceId: String): Boolean = false

        override suspend fun trustedDevices(): Set<TrustedDevice> = emptySet()

        override suspend fun registerTrustedDevice(device: TrustedDevice) {
            lastRegisteredDevice = device
        }

        override suspend fun revokeTrustedDevice(deviceId: String) {
            // no-op
        }
    }

    private class FakeWatchdogEngine : WatchdogEngine {
        var lastHeartbeatComponent: String? = null
        var lastRecoveryRequest: RecoveryRequest? = null
        private val _health = MutableStateFlow(
            WatchdogHealthSnapshot(
                started = false,
                generatedAtEpochMs = 0L,
                components = emptyMap()
            )
        )
        private val _events = MutableSharedFlow<RecoveryRequest>()

        override val health = _health.asStateFlow()
        override val recoveryEvents: Flow<RecoveryRequest> = _events.asSharedFlow()

        override suspend fun start() {
            // no-op
        }

        override suspend fun stop() {
            // no-op
        }

        override suspend fun registerComponent(component: String, timeoutMs: Long) {
            _health.value = _health.value.copy(
                components = _health.value.components + (
                    component to WatchdogComponentHealth(
                        component = component,
                        status = WatchdogComponentStatus.STARTING,
                        heartbeatTimeoutMs = timeoutMs
                    )
                    )
            )
        }

        override suspend fun updateComponentStatus(
            component: String,
            status: WatchdogComponentStatus,
            details: String?
        ) {
            val existing = _health.value.components[component] ?: WatchdogComponentHealth(component = component)
            _health.value = _health.value.copy(
                components = _health.value.components + (
                    component to existing.copy(
                        status = status,
                        lastDetails = details
                    )
                    )
            )
        }

        override suspend fun heartbeat(component: String) {
            lastHeartbeatComponent = component
        }

        override suspend fun requestRecovery(request: RecoveryRequest) {
            lastRecoveryRequest = request
        }
    }
}
