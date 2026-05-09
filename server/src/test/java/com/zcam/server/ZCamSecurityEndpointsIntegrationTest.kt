package com.zcam.server

import com.zcam.audio.AudioCommandResult
import com.zcam.audio.AudioLiveMode
import com.zcam.audio.AudioPlaybackRequest
import com.zcam.audio.AudioStateSnapshot
import com.zcam.audio.PushToTalkManager
import com.zcam.camera.CameraControlCommandResult
import com.zcam.camera.CameraControlManager
import com.zcam.camera.CameraControlsSnapshot
import com.zcam.camera.FramePipelineStatus
import com.zcam.camera.FramePipelineStatusSource
import com.zcam.camera.MjpegFrameSource
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.audio.PlaybackSource
import com.zcam.core.domain.config.FeatureFlag
import com.zcam.core.domain.config.RuntimeSettings
import com.zcam.core.domain.config.RuntimeSettingsDefaults
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.domain.settings.RuntimeSettingsRepository
import com.zcam.core.domain.settings.RuntimeSettingsUpdateResult
import com.zcam.core.logging.ZCamLogger
import com.zcam.security.LanAccessPolicy
import com.zcam.security.LocalSecurityManager
import com.zcam.security.SecurityTokenStore
import com.zcam.security.StoredApiToken
import com.zcam.storage.LoopRecordingManager
import com.zcam.storage.RecordingClipSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class ZCamSecurityEndpointsIntegrationTest {

    private val client = OkHttpClient()
    private val logger = NoopLogger()
    private val dispatchers = TestDispatcherProvider()
    private val runtimeSettingsRepository = FakeRuntimeSettingsRepository(RuntimeSettingsDefaults.value)
    private val tokenStore = InMemoryTokenStore()

    private lateinit var securityManager: LocalSecurityManager
    private lateinit var httpServer: ZCamHttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        securityManager = LocalSecurityManager(
            runtimeSettingsRepository = runtimeSettingsRepository,
            tokenStore = tokenStore,
            logger = logger
        )
        httpServer = ZCamHttpServer(
            frameSource = FakeFrameSource(),
            frameStatusSource = FakeFramePipelineStatusSource(),
            cameraControlManager = FakeCameraControlManager(),
            pushToTalkManager = FakeAudioManager(),
            loopRecordingManager = NoopLoopRecordingManager(),
            securityManager = securityManager,
            dispatchers = dispatchers,
            logger = logger,
            lanAccessPolicy = LanAccessPolicy()
        )
        port = findFreePort()
        runBlocking {
            httpServer.start(port)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            httpServer.stop()
        }
    }

    @Test
    fun rejects_unauthorized_access_to_protected_endpoint() {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/api/status")
            .get()
            .build()
        val response = client.newCall(request).execute()
        response.use {
            assertEquals(401, it.code)
            assertTrue(it.body?.string().orEmpty().contains("missing_token"))
        }
    }

    @Test
    fun rejects_wrong_token() {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port/api/status")
            .get()
            .header("X-ZCam-Token", "wrong-token")
            .build()
        val response = client.newCall(request).execute()
        response.use {
            assertEquals(401, it.code)
            assertTrue(it.body?.string().orEmpty().contains("invalid_token"))
        }
    }

    @Test
    fun simplified_pairing_flow_issues_token_after_code_confirmation() {
        val requestResponseBody = postJson(
            "/api/security/pair/request",
            """{"deviceId":"browser-1","displayName":"Browser","clientType":"web_browser"}"""
        ).use {
            assertEquals(200, it.code)
            it.body?.string().orEmpty()
        }
        val requestId = extractJsonString(requestResponseBody, "requestId")
        val verificationCode = runBlocking {
            securityManager.pendingPairingRequests
                .first { requests -> requests.any { it.requestId == requestId } }
                .single { it.requestId == requestId }
                .verificationCode
        }

        postJson(
            "/api/security/pair/complete",
            """{"requestId":"$requestId","verificationCode":"000000"}"""
        ).use {
            assertEquals(401, it.code)
            assertTrue(it.body?.string().orEmpty().contains("invalid_pairing_code"))
        }

        val completeResponseBody = postJson(
            "/api/security/pair/complete",
            """{"requestId":"$requestId","verificationCode":"$verificationCode"}"""
        ).use {
            assertEquals(200, it.code)
            it.body?.string().orEmpty()
        }
        val issuedToken = extractJsonString(completeResponseBody, "token")

        val statusRequest = Request.Builder()
            .url("http://127.0.0.1:$port/api/status")
            .get()
            .header("X-ZCam-Token", issuedToken)
            .header("X-ZCam-Device-Id", "browser-1")
            .build()
        client.newCall(statusRequest).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun blocks_pairing_replay_and_accepts_valid_token_after_pairing() {
        val qrResponse = Request.Builder()
            .url("http://127.0.0.1:$port/api/security/pair/qr")
            .get()
            .build()
            .let { client.newCall(it).execute() }

        val qrBody = qrResponse.use {
            assertEquals(200, it.code)
            it.body?.string().orEmpty()
        }
        val sessionId = extractJsonString(qrBody, "sessionId")
        val pairingCode = extractJsonString(qrBody, "pairingCode")

        val pairBody = """
            {"pin":"${RuntimeSettingsDefaults.value.security.pinCode}","sessionId":"$sessionId","pairingCode":"$pairingCode","deviceId":"phone-1","displayName":"Phone"}
        """.trimIndent()

        val firstPair = postJson("/api/security/pair", pairBody)
        val firstPairResponseBody = firstPair.use {
            assertEquals(200, it.code)
            it.body?.string().orEmpty()
        }
        val issuedToken = extractJsonString(firstPairResponseBody, "token")

        val replayPair = postJson("/api/security/pair", pairBody)
        replayPair.use {
            assertEquals(409, it.code)
            assertTrue(it.body?.string().orEmpty().contains("pairing_replay_detected"))
        }

        val statusRequest = Request.Builder()
            .url("http://127.0.0.1:$port/api/status")
            .get()
            .header("X-ZCam-Token", issuedToken)
            .header("X-ZCam-Device-Id", "phone-1")
            .build()
        val statusResponse = client.newCall(statusRequest).execute()
        statusResponse.use {
            assertEquals(200, it.code)
        }
    }

    private fun postJson(path: String, body: String) = client.newCall(
        Request.Builder()
            .url("http://127.0.0.1:$port$path")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
    ).execute()

    private fun extractJsonString(body: String, key: String): String {
        val regex = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(body)?.groupValues?.get(1)
            ?: error("Missing key=$key in body: $body")
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private class FakeFrameSource : MjpegFrameSource {
        override fun latestFrame(): ByteArray = byteArrayOf(1, 2, 3)
    }

    private class FakeFramePipelineStatusSource : FramePipelineStatusSource {
        override fun snapshot(): FramePipelineStatus = FramePipelineStatus(
            running = true,
            targetWidth = 1280,
            targetHeight = 720,
            targetFps = 15,
            producedFrames = 0L,
            droppedFrames = 0L,
            lastFrameEpochMs = System.currentTimeMillis()
        )
    }

    private class FakeCameraControlManager : CameraControlManager {
        private var snapshot = CameraControlsSnapshot(
            running = true,
            torchEnabled = false,
            nightModeEnabled = false,
            lowLightBoostSupported = true,
            lastError = null
        )

        override suspend fun setTorch(enabled: Boolean): CameraControlCommandResult {
            snapshot = snapshot.copy(torchEnabled = enabled, lastError = null)
            return CameraControlCommandResult.Success(snapshot, "ok")
        }

        override suspend fun setNightMode(enabled: Boolean): CameraControlCommandResult {
            snapshot = snapshot.copy(nightModeEnabled = enabled, lastError = null)
            return CameraControlCommandResult.Success(snapshot, "ok")
        }

        override fun controlsSnapshot(): CameraControlsSnapshot = snapshot
    }

    private class FakeAudioManager : PushToTalkManager {
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun isHealthy(): Boolean = true
        override suspend fun beginPushToTalk() = Unit
        override suspend fun endPushToTalk() = Unit
        override suspend fun beginLiveListen() = Unit
        override suspend fun stopLiveListen() = Unit
        override suspend fun startPlayback(source: PlaybackSource) = Unit
        override suspend fun stopPlayback() = Unit
        override suspend fun handleLiveMode(mode: AudioLiveMode, enabled: Boolean): AudioCommandResult {
            return AudioCommandResult.Success(snapshotState(), "ok")
        }

        override suspend fun playStoredAudio(request: AudioPlaybackRequest): AudioCommandResult {
            return AudioCommandResult.Success(snapshotState(), "ok")
        }

        override suspend fun setVolume(levelPercent: Int): AudioCommandResult {
            return AudioCommandResult.Success(snapshotState(), "ok")
        }

        override fun snapshotState(): AudioStateSnapshot = AudioStateSnapshot(
            engineStarted = true,
            transmitting = false,
            liveListening = false,
            playingBack = false,
            activeClipId = null,
            volumePercent = 40,
            minVolumePercent = 0,
            maxVolumePercent = 85,
            aversiveCooldownMs = 10_000L,
            aversiveCooldownRemainingMs = 0L
        )
    }

    private class TestDispatcherProvider : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private class InMemoryTokenStore : SecurityTokenStore {
        private var tokens: List<StoredApiToken> = emptyList()
        override suspend fun readTokens(): List<StoredApiToken> = tokens
        override suspend fun writeTokens(tokens: List<StoredApiToken>) {
            this.tokens = tokens
        }
    }

    private class FakeRuntimeSettingsRepository(
        initial: RuntimeSettings
    ) : RuntimeSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<RuntimeSettings> = state

        override suspend fun updateSettings(candidate: RuntimeSettings): RuntimeSettingsUpdateResult {
            state.value = candidate
            return RuntimeSettingsUpdateResult.Success(candidate)
        }

        override suspend fun setFeatureFlag(
            flag: FeatureFlag,
            enabled: Boolean
        ): RuntimeSettingsUpdateResult {
            val next = state.value.copy(featureFlags = state.value.featureFlags.withFlag(flag, enabled))
            state.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }

        override suspend fun upsertTrustedDevice(device: TrustedDevice): RuntimeSettingsUpdateResult {
            val nextDevices = state.value.security.trustedDevices
                .filterNot { it.deviceId == device.deviceId }
                .toSet() + device
            val next = state.value.copy(security = state.value.security.copy(trustedDevices = nextDevices))
            state.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }

        override suspend fun removeTrustedDevice(deviceId: String): RuntimeSettingsUpdateResult {
            val next = state.value.copy(
                security = state.value.security.copy(
                    trustedDevices = state.value.security.trustedDevices.filterNot { it.deviceId == deviceId }.toSet()
                )
            )
            state.value = next
            return RuntimeSettingsUpdateResult.Success(next)
        }
    }

    private class NoopLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }

    private class NoopLoopRecordingManager : LoopRecordingManager {
        override suspend fun start(config: com.zcam.core.domain.config.LoopRecordingConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun forceRetentionSweep() = Unit
        override suspend fun isHealthy(): Boolean = true
        override suspend fun queryRecordings(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<RecordingClipSummary> = emptyList()

        override suspend fun queryRecordingEvents(
            fromEpochMs: Long?,
            toEpochMs: Long?,
            limit: Int
        ): List<com.zcam.storage.RecordingEventSummary> = emptyList()

        override suspend fun resolveRecordingFile(fileName: String): java.io.File? = null
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
