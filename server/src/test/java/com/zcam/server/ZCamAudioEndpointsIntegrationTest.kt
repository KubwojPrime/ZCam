package com.zcam.server

import com.zcam.audio.AndroidPushToTalkManager
import com.zcam.audio.SystemVolumeApplyResult
import com.zcam.audio.SystemVolumeController
import com.zcam.camera.CameraControlCommandResult
import com.zcam.camera.CameraControlErrorCode
import com.zcam.camera.CameraControlManager
import com.zcam.camera.CameraControlsSnapshot
import com.zcam.camera.FramePipelineStatus
import com.zcam.camera.FramePipelineStatusSource
import com.zcam.camera.MjpegFrameSource
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.TrustedDevice
import com.zcam.core.logging.ZCamLogger
import com.zcam.security.LanAccessPolicy
import com.zcam.security.PairingActionResult
import com.zcam.security.PairingChallenge
import com.zcam.security.PairingClientType
import com.zcam.security.PairingRequestStartResult
import com.zcam.security.PairingResult
import com.zcam.security.PendingPairingRequest
import com.zcam.security.SecurityAuthDecision
import com.zcam.security.SecurityManager
import com.zcam.security.TokenRevocationResult
import com.zcam.security.TokenRotationResult
import com.zcam.storage.LoopRecordingManager
import com.zcam.storage.RecordingClipSummary
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class ZCamAudioEndpointsIntegrationTest {

    private val client = OkHttpClient()
    private val logger = NoopLogger()
    private val dispatchers = TestDispatcherProvider()

    private lateinit var audioManager: AndroidPushToTalkManager
    private lateinit var volumeController: FakeSystemVolumeController
    private lateinit var cameraControlManager: FakeCameraControlManager
    private val securityManager = AllowAllSecurityManager()
    private lateinit var httpServer: ZCamHttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        volumeController = FakeSystemVolumeController()
        audioManager = AndroidPushToTalkManager(
            dispatchers = dispatchers,
            logger = logger,
            systemVolumeController = volumeController
        )
        cameraControlManager = FakeCameraControlManager()
        httpServer = ZCamHttpServer(
            frameSource = FakeFrameSource(),
            frameStatusSource = FakeFramePipelineStatusSource(),
            cameraControlManager = cameraControlManager,
            pushToTalkManager = audioManager,
            loopRecordingManager = NoopLoopRecordingManager(),
            securityManager = securityManager,
            dispatchers = dispatchers,
            logger = logger,
            lanAccessPolicy = LanAccessPolicy()
        )
        port = findFreePort()
        runBlocking {
            audioManager.start()
            httpServer.start(port)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            httpServer.stop()
            audioManager.stop()
        }
    }

    @Test
    fun audio_live_endpoint_enables_push_to_talk() {
        val response = postJson(
            path = "/api/audio/live",
            body = """{"mode":"ptt","enabled":true}"""
        )

        response.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(200, it.code)
            assertTrue(payload.contains("\"status\":\"ok\""))
            assertTrue(payload.contains("\"transmitting\":true"))
            assertTrue(payload.contains("\"liveListening\":false"))
        }
    }

    @Test
    fun volume_endpoint_enforces_safe_limits() {
        val invalid = postJson(
            path = "/api/volume",
            body = """{"level":90}"""
        )
        invalid.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(400, it.code)
            assertTrue(payload.contains("volume must be between 0 and 85"))
        }

        val valid = postJson(
            path = "/api/volume",
            body = """{"level":80}"""
        )
        valid.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(200, it.code)
            assertTrue(payload.contains("\"volumePercent\":80"))
        }
        assertEquals(80, volumeController.lastRequestedPercent ?: -1)
    }

    @Test
    fun volume_endpoint_reports_system_volume_failure() {
        volumeController.nextFailureReason = "volume service unavailable"

        val response = postJson(
            path = "/api/volume",
            body = """{"level":60}"""
        )

        response.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(503, it.code)
            assertTrue(payload.contains("\"code\":\"system_volume_unavailable\""))
            assertTrue(payload.contains("volume service unavailable"))
        }
    }

    @Test
    fun audio_play_endpoint_applies_aversive_cooldown() {
        val first = postJson(
            path = "/api/audio/play",
            body = """{"clipId":"alarm_01","category":"aversive"}"""
        )
        first.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(200, it.code)
            assertTrue(payload.contains("\"status\":\"ok\""))
            assertTrue(payload.contains("\"activeClipId\":\"alarm_01\""))
        }

        val second = postJson(
            path = "/api/audio/play",
            body = """{"clipId":"alarm_02","category":"aversive"}"""
        )
        second.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(429, it.code)
            assertTrue(payload.contains("\"code\":\"cooldown_active\""))
        }
    }

    @Test
    fun audio_commands_return_service_unavailable_when_engine_stopped() {
        runBlocking {
            audioManager.stop()
        }

        val response = postJson(
            path = "/api/audio/live",
            body = """{"mode":"live","enabled":true}"""
        )

        response.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(503, it.code)
            assertTrue(payload.contains("\"code\":\"engine_not_ready\""))
        }
    }

    @Test
    fun torch_endpoint_updates_camera_controls_state() {
        val response = postJson(
            path = "/api/torch",
            body = """{"enabled":true}"""
        )

        response.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(200, it.code)
            assertTrue(payload.contains("\"status\":\"ok\""))
            assertTrue(payload.contains("\"torchEnabled\":true"))
        }
    }

    @Test
    fun nightmode_endpoint_maps_conflict_error_to_http_409() {
        cameraControlManager.nextNightModeFailure = CameraControlCommandResult.Failure(
            code = CameraControlErrorCode.CONFLICT,
            message = "night mode conflict",
            snapshot = cameraControlManager.controlsSnapshot()
        )

        val response = postJson(
            path = "/api/nightmode",
            body = """{"enabled":true}"""
        )
        response.use {
            val payload = it.body?.string().orEmpty()
            assertEquals(409, it.code)
            assertTrue(payload.contains("\"code\":\"conflict\""))
            assertTrue(payload.contains("night mode conflict"))
        }
    }

    private fun postJson(path: String, body: String): okhttp3.Response {
        val request = Request.Builder()
            .url("http://127.0.0.1:$port$path")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).execute()
    }

    private fun findFreePort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private class FakeFrameSource : MjpegFrameSource {
        override fun latestFrame(): ByteArray = FALLBACK_FRAME
    }

    private class FakeFramePipelineStatusSource : FramePipelineStatusSource {
        override fun snapshot(): FramePipelineStatus = FramePipelineStatus(
            running = true,
            targetWidth = 1280,
            targetHeight = 720,
            targetFps = 15,
            producedFrames = 100L,
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
        var nextNightModeFailure: CameraControlCommandResult.Failure? = null

        override suspend fun setTorch(enabled: Boolean): CameraControlCommandResult {
            snapshot = snapshot.copy(torchEnabled = enabled, lastError = null)
            return CameraControlCommandResult.Success(snapshot, "torch updated")
        }

        override suspend fun setNightMode(enabled: Boolean): CameraControlCommandResult {
            val failure = nextNightModeFailure
            if (failure != null) {
                nextNightModeFailure = null
                snapshot = failure.snapshot
                return failure
            }
            snapshot = snapshot.copy(nightModeEnabled = enabled, lastError = null)
            return CameraControlCommandResult.Success(snapshot, "night mode updated")
        }

        override fun controlsSnapshot(): CameraControlsSnapshot = snapshot
    }

    private class TestDispatcherProvider : DispatcherProvider {
        override val io: CoroutineDispatcher = Dispatchers.IO
        override val default: CoroutineDispatcher = Dispatchers.Default
    }

    private class FakeSystemVolumeController : SystemVolumeController {
        var lastRequestedPercent: Int? = null
        var nextFailureReason: String? = null

        override fun currentMusicVolumePercent(): Int? = lastRequestedPercent

        override fun setMusicVolumePercent(levelPercent: Int): SystemVolumeApplyResult {
            val failure = nextFailureReason
            if (failure != null) {
                nextFailureReason = null
                return SystemVolumeApplyResult(
                    applied = false,
                    actualPercent = lastRequestedPercent,
                    streamVolume = null,
                    streamMaxVolume = 15,
                    reason = failure
                )
            }

            lastRequestedPercent = levelPercent
            return SystemVolumeApplyResult(
                applied = true,
                actualPercent = levelPercent,
                streamVolume = levelPercent,
                streamMaxVolume = 100
            )
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

        override suspend fun resolveRecordingFile(fileName: String): java.io.File? = null
    }

    private class AllowAllSecurityManager : SecurityManager {
        override val pendingPairingRequests = MutableStateFlow<List<PendingPairingRequest>>(emptyList())

        override suspend fun authorizeRequest(tokenCandidate: String?, deviceId: String?): SecurityAuthDecision {
            return SecurityAuthDecision(
                allowed = true,
                statusCode = 200,
                reason = "ok",
                tokenId = "test-token",
                deviceId = deviceId
            )
        }

        override suspend fun requestPairing(
            deviceId: String,
            displayName: String,
            clientType: PairingClientType
        ): PairingRequestStartResult = PairingRequestStartResult.Success(
            requestId = "request-1",
            deviceId = deviceId,
            displayName = displayName,
            expiresAtEpochMs = Long.MAX_VALUE
        )

        override suspend fun completePairingRequest(
            requestId: String,
            verificationCode: String
        ): PairingResult = PairingResult.Success(
            tokenId = "test-token",
            tokenValue = "test-secret",
            deviceId = "browser-1"
        )

        override suspend fun cancelPairingRequest(requestId: String): PairingActionResult {
            return PairingActionResult.Success()
        }

        override suspend fun createPairingChallenge(): PairingChallenge {
            return PairingChallenge(
                sessionId = "sid",
                pairingCode = "code",
                createdAtEpochMs = 0L,
                expiresAtEpochMs = Long.MAX_VALUE
            )
        }

        override suspend fun pairDevice(
            pin: String,
            sessionId: String,
            pairingCode: String,
            deviceId: String,
            displayName: String
        ): PairingResult = PairingResult.Success(
            tokenId = "test-token",
            tokenValue = "test-secret",
            deviceId = deviceId
        )

        override suspend fun rotateToken(
            requesterToken: String?,
            requesterDeviceId: String?,
            revokeCurrent: Boolean
        ): TokenRotationResult = TokenRotationResult.Success(
            tokenId = "test-token-2",
            tokenValue = "test-secret-2"
        )

        override suspend fun revokeToken(
            requesterToken: String?,
            requesterDeviceId: String?,
            tokenIdToRevoke: String
        ): TokenRevocationResult = TokenRevocationResult.Success(tokenIdToRevoke)

        override suspend fun sanityCheckAfterRestart() = Unit

        override suspend fun validatePin(candidate: String): Boolean = true
        override suspend fun validateToken(candidate: String): Boolean = true
        override suspend fun isTrustedDevice(deviceId: String): Boolean = true
        override suspend fun trustedDevices(): Set<TrustedDevice> = emptySet()
        override suspend fun registerTrustedDevice(device: TrustedDevice) = Unit
        override suspend fun revokeTrustedDevice(deviceId: String) = Unit
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // 1x1 JPEG, used only to keep snapshot endpoint valid in integration tests.
        val FALLBACK_FRAME = java.util.Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxAQEBIQEA8PEA8PDw8PDw8PDw8PDw8PFREWFhURFRUYHSggGBolGxUVITEhJSkrLi4uFx8zODMsNygtLisBCgoKDg0OGhAQGi0mHyYtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAAEAAQMBEQACEQEDEQH/xAAXAAEAAwAAAAAAAAAAAAAAAAAAAQID/8QAFhEBAQEAAAAAAAAAAAAAAAAAAAER/9oADAMBAAIQAxAAAAG0A//EABkQAQADAQEAAAAAAAAAAAAAAAIAAREhMf/aAAgBAQABBQJQ0ZQ4x4f/xAAVEQEBAAAAAAAAAAAAAAAAAAAAEf/aAAgBAwEBPwFH/8QAFhEBAQEAAAAAAAAAAAAAAAAAABEh/9oACAECAQE/AYf/xAAbEAACAgMBAAAAAAAAAAAAAAABEQAhMUFhcf/aAAgBAQAGPwKQ4Yq0cYv/xAAZEAEAAgMAAAAAAAAAAAAAAAABABEhMUH/2gAIAQEAAT8h0qVI0rWg/wD/2Q=="
        )
    }
}
