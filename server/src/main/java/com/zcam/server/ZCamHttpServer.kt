package com.zcam.server

import com.zcam.audio.AudioCommandErrorCode
import com.zcam.audio.AudioCommandResult
import com.zcam.audio.AudioLiveMode
import com.zcam.audio.AudioPlaybackCategory
import com.zcam.audio.AudioPlaybackRequest
import com.zcam.audio.AudioTransportConfig
import com.zcam.audio.PushToTalkManager
import com.zcam.camera.CameraControlCommandResult
import com.zcam.camera.CameraControlErrorCode
import com.zcam.camera.CameraControlManager
import com.zcam.camera.FramePipelineStatusSource
import com.zcam.camera.MjpegFrameSource
import com.zcam.core.device.PowerStatusProvider
import com.zcam.core.device.PowerStatusSnapshot
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import com.zcam.security.LanAccessPolicy
import com.zcam.security.PairingClientType
import com.zcam.security.PairingRequestStartResult
import com.zcam.security.PairingResult
import com.zcam.security.SecurityManager
import com.zcam.security.TokenRevocationResult
import com.zcam.security.TokenRotationResult
import com.zcam.storage.LoopRecordingManager
import com.zcam.storage.RecordingClipSummary
import com.zcam.storage.RecordingEventSummary
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZCamHttpServer @Inject constructor(
    private val frameSource: MjpegFrameSource,
    private val frameStatusSource: FramePipelineStatusSource,
    private val cameraControlManager: CameraControlManager,
    private val pushToTalkManager: PushToTalkManager,
    private val loopRecordingManager: LoopRecordingManager,
    private val securityManager: SecurityManager,
    private val powerStatusProvider: PowerStatusProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger,
    private val lanAccessPolicy: LanAccessPolicy
) : LocalHttpServer {

    @Volatile
    private var server: NanoHTTPD? = null

    @Volatile
    private var activePort: Int = DEFAULT_PORT

    private val activeStreamClients = AtomicInteger(0)
    private val startedAtEpochMs = AtomicLong(0L)
    private val websocketScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    override suspend fun start(port: Int) = withContext(dispatchers.io) {
        if (server != null) return@withContext

        securityManager.sanityCheckAfterRestart()
        activePort = port
        val httpServer = object : NanoWSD(port) {
            override fun serve(session: IHTTPSession): Response {
                return authorizeAndHandleSession(session) {
                    if (isWebsocketRequested(session) && session.uri.orEmpty() != AUDIO_SOCKET_PATH) {
                        return@authorizeAndHandleSession newFixedLengthResponse(
                            Response.Status.NOT_FOUND,
                            MIME_PLAINTEXT,
                            "not found"
                        )
                    }
                    super.serve(session)
                }
            }

            override fun serveHttp(session: IHTTPSession): Response = routeRequest(session)

            override fun openWebSocket(handshake: IHTTPSession): WebSocket {
                return createAudioWebSocket(handshake)
            }
        }

        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        startedAtEpochMs.set(System.currentTimeMillis())
        server = httpServer
        logger.i(LogEventId.COMPONENT_HEALTHY, "HTTP server started on port $activePort")
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        server?.stop()
        server = null
        activeStreamClients.set(0)
        startedAtEpochMs.set(0L)
        logger.i(LogEventId.COMPONENT_STOPPED, "HTTP server stopped")
    }

    override suspend fun isHealthy(): Boolean = withContext(dispatchers.io) {
        server?.isAlive == true
    }

    private fun authorizeAndHandleSession(
        session: NanoHTTPD.IHTTPSession,
        handler: () -> NanoHTTPD.Response
    ): NanoHTTPD.Response {
        val uri = session.uri.orEmpty()
        val isPublic = isPublicEndpoint(uri, session.method)
        val remoteIp = session.remoteIpAddress
        val isLanOrVpnClient = lanAccessPolicy.isLanClient(remoteIp)
        val allowPublicOverVpn = isPublicPairingEndpoint(uri, session.method)

        if (!isLanOrVpnClient && isPublic && !allowPublicOverVpn) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.FORBIDDEN,
                NanoHTTPD.MIME_PLAINTEXT,
                "LAN only"
            )
        }

        if (!isPublic) {
            val auth = runBlocking {
                securityManager.authorizeRequest(
                    tokenCandidate = extractToken(session),
                    deviceId = extractDeviceId(session)
                )
            }
            if (!auth.allowed) {
                return NanoHTTPD.newFixedLengthResponse(
                    toStatus(auth.statusCode),
                    JSON_UTF8,
                    "{\"status\":\"error\",\"reason\":${jsonString(auth.reason)}}"
                )
            }
            if (!isLanOrVpnClient) {
                logger.w(
                    LogEventId.SECURITY_AUTH_REJECTED,
                    "Allowing authenticated request from non-LAN address $remoteIp for $uri"
                )
            }
        }

        return runCatching(handler).getOrElse { error ->
            logger.e(LogEventId.COMPONENT_FAILED, error, "HTTP request handling failed for ${session.uri}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_PLAINTEXT,
                "internal error"
            )
        }
    }

    private fun createAudioWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket {
        return AudioWebSocket(handshake)
    }

    private inner class AudioWebSocket(
        handshake: NanoHTTPD.IHTTPSession
    ) : NanoWSD.WebSocket(handshake) {

        private val role = parseAudioSocketRole(handshake)
        private val connectionId = buildAudioSocketConnectionId(handshake)
        private val outboundFrames = Channel<ByteArray>(
            capacity = AUDIO_SOCKET_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        private var outboundJob: Job? = null

        override fun onOpen() {
            when (role) {
                AudioSocketRole.LIVE -> {
                    val accepted = runBlocking {
                        pushToTalkManager.registerLiveAudioSubscriber(connectionId) { frame ->
                            outboundFrames.trySend(frame)
                        }
                    }
                    if (!accepted) {
                        rejectSocket("live audio unavailable")
                        return
                    }
                    outboundJob = websocketScope.launch {
                        for (frame in outboundFrames) {
                            val sent = runCatching {
                                send(frame)
                            }.isSuccess
                            if (!sent) {
                                rejectSocket("live audio send failed")
                                break
                            }
                        }
                    }
                    sendConfigFrame()
                }

                AudioSocketRole.PTT -> {
                    val accepted = runBlocking { pushToTalkManager.openPushToTalkStream(connectionId) }
                    if (!accepted) {
                        rejectSocket("push-to-talk unavailable")
                        return
                    }
                    sendConfigFrame()
                }

                AudioSocketRole.UNKNOWN -> rejectSocket("unsupported audio role")
            }
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            outboundFrames.close()
            outboundJob?.cancel()
            websocketScope.launch {
                when (role) {
                    AudioSocketRole.LIVE -> pushToTalkManager.unregisterLiveAudioSubscriber(connectionId)
                    AudioSocketRole.PTT -> pushToTalkManager.closePushToTalkStream(connectionId)
                    AudioSocketRole.UNKNOWN -> Unit
                }
            }
        }

        override fun onMessage(message: NanoWSD.WebSocketFrame) {
            if (role != AudioSocketRole.PTT) return
            if (message.opCode != NanoWSD.WebSocketFrame.OpCode.Binary) return

            val accepted = runBlocking {
                pushToTalkManager.submitPushToTalkAudio(connectionId, message.binaryPayload)
            }
            if (!accepted) {
                rejectSocket("push-to-talk frame rejected")
            }
        }

        override fun onPong(pong: NanoWSD.WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            logger.w(
                LogEventId.COMPONENT_FAILED,
                "Audio websocket exception role=${role.name.lowercase()} message=${exception.message}"
            )
        }

        private fun sendConfigFrame() {
            val config = pushToTalkManager.transportConfig()
            runCatching {
                send(audioSocketConfigJson(config, role))
            }.onFailure {
                rejectSocket("audio config send failed")
            }
        }

        private fun rejectSocket(reason: String) {
            runCatching {
                close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, reason, false)
            }
        }
    }

    private fun routeRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.orEmpty()
        return when {
            uri == "/api/recordings/events" -> handleRecordingEventsEndpoint(session)
            uri.startsWith("/api/recordings/") -> handleRecordingDownloadEndpoint(session)
            else -> when (uri) {
            "/" -> buildPanelResponse()
            "/health" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "ok")
            "/api/status" -> buildStatusResponse()
            "/api/recordings" -> handleRecordingsListEndpoint(session)
            "/video", "/mjpeg" -> buildVideoResponse()
            "/snapshot.jpg" -> buildSnapshotResponse()
            "/api/torch" -> handleTorchEndpoint(session)
            "/api/nightmode" -> handleNightModeEndpoint(session)
            "/api/audio/live" -> handleAudioLiveEndpoint(session)
            "/api/audio/play" -> handleAudioPlayEndpoint(session)
            "/api/volume" -> handleVolumeEndpoint(session)
            "/api/security/pair/qr" -> handlePairingQrEndpoint(session)
            "/api/security/pair/request" -> handlePairingRequestEndpoint(session)
            "/api/security/pair/complete" -> handlePairingCompleteEndpoint(session)
            "/api/security/pair" -> handlePairDeviceEndpoint(session)
            "/api/security/token/rotate" -> handleTokenRotateEndpoint(session)
            "/api/security/token/revoke" -> handleTokenRevokeEndpoint(session)
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
            }
        }
    }

    private fun handlePairingRequestEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val deviceId = valueOf(payload, "deviceId")?.trim().orEmpty()
        val displayName = valueOf(payload, "displayName", "name")?.trim().orEmpty()
        val clientType = parsePairingClientType(valueOf(payload, "clientType", "type"))
            ?: return badRequest("required: clientType in {android_app, web_browser}")
        if (deviceId.isBlank() || displayName.isBlank()) {
            return badRequest("required: deviceId, displayName, clientType")
        }

        return when (val result = runBlocking {
            securityManager.requestPairing(
                deviceId = deviceId,
                displayName = displayName,
                clientType = clientType
            )
        }) {
            is PairingRequestStartResult.Success -> {
                val body = buildString {
                    append('{')
                    append("\"status\":\"ok\",")
                    append("\"requestId\":").append(jsonString(result.requestId)).append(',')
                    append("\"deviceId\":").append(jsonString(result.deviceId)).append(',')
                    append("\"displayName\":").append(jsonString(result.displayName)).append(',')
                    append("\"expiresAtEpochMs\":").append(result.expiresAtEpochMs)
                    append('}')
                }
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
            }
            is PairingRequestStartResult.Failure -> {
                NanoHTTPD.newFixedLengthResponse(
                    toStatus(result.statusCode),
                    JSON_UTF8,
                    "{\"status\":\"error\",\"reason\":${jsonString(result.reason)}}"
                )
            }
        }
    }

    private fun handlePairingCompleteEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val requestId = valueOf(payload, "requestId")?.trim().orEmpty()
        val verificationCode = valueOf(payload, "verificationCode", "code")?.trim().orEmpty()
        if (requestId.isBlank() || verificationCode.isBlank()) {
            return badRequest("required: requestId, verificationCode")
        }

        return when (val result = runBlocking {
            securityManager.completePairingRequest(
                requestId = requestId,
                verificationCode = verificationCode
            )
        }) {
            is PairingResult.Success -> pairingSuccessResponse(result)
            is PairingResult.Failure -> {
                NanoHTTPD.newFixedLengthResponse(
                    toStatus(result.statusCode),
                    JSON_UTF8,
                    "{\"status\":\"error\",\"reason\":${jsonString(result.reason)}}"
                )
            }
        }
    }

    private fun handlePairingQrEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET) {
            return methodNotAllowed("GET")
        }

        val challenge = runBlocking { securityManager.createPairingChallenge() }
        val host = resolvePairingHost(session.headers["host"])
        val payload = buildString {
            append("zcam://pair?")
            append("sid=").append(challenge.sessionId)
            append("&code=").append(challenge.pairingCode)
            append("&host=").append(host)
        }

        val body = buildString {
            append('{')
            append("\"status\":\"ok\",")
            append("\"sessionId\":").append(jsonString(challenge.sessionId)).append(',')
            append("\"pairingCode\":").append(jsonString(challenge.pairingCode)).append(',')
            append("\"createdAtEpochMs\":").append(challenge.createdAtEpochMs).append(',')
            append("\"expiresAtEpochMs\":").append(challenge.expiresAtEpochMs).append(',')
            append("\"qrPayload\":").append(jsonString(payload))
            append('}')
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
    }

    private fun handlePairDeviceEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val pin = valueOf(payload, "pin")?.trim().orEmpty()
        val sessionId = valueOf(payload, "sessionId", "sid")?.trim().orEmpty()
        val pairingCode = valueOf(payload, "pairingCode", "code")?.trim().orEmpty()
        val deviceId = valueOf(payload, "deviceId")?.trim().orEmpty()
        val displayName = valueOf(payload, "displayName", "name")?.trim().orEmpty()
        if (pin.isBlank() || sessionId.isBlank() || pairingCode.isBlank() || deviceId.isBlank() || displayName.isBlank()) {
            return badRequest("required: pin, sessionId, pairingCode, deviceId, displayName")
        }

        return when (val result = runBlocking {
            securityManager.pairDevice(
                pin = pin,
                sessionId = sessionId,
                pairingCode = pairingCode,
                deviceId = deviceId,
                displayName = displayName
            )
        }) {
            is PairingResult.Success -> pairingSuccessResponse(result)
            is PairingResult.Failure -> {
                NanoHTTPD.newFixedLengthResponse(
                    toStatus(result.statusCode),
                    JSON_UTF8,
                    "{\"status\":\"error\",\"reason\":${jsonString(result.reason)}}"
                )
            }
        }
    }

    private fun pairingSuccessResponse(result: PairingResult.Success): NanoHTTPD.Response {
        val body = buildString {
            append('{')
            append("\"status\":\"ok\",")
            append("\"tokenId\":").append(jsonString(result.tokenId)).append(',')
            append("\"token\":").append(jsonString(result.tokenValue)).append(',')
            append("\"deviceId\":").append(jsonString(result.deviceId))
            append('}')
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
    }

    private fun handleTokenRotateEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }
        val payload = parsePostPayload(session).orEmpty()
        val revokeCurrent = parseBoolean(valueOf(payload, "revokeCurrent")) ?: true

        return when (val result = runBlocking {
            securityManager.rotateToken(
                requesterToken = extractToken(session),
                requesterDeviceId = extractDeviceId(session),
                revokeCurrent = revokeCurrent
            )
        }) {
            is TokenRotationResult.Success -> {
                val body = buildString {
                    append('{')
                    append("\"status\":\"ok\",")
                    append("\"tokenId\":").append(jsonString(result.tokenId)).append(',')
                    append("\"token\":").append(jsonString(result.tokenValue))
                    append('}')
                }
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
            }
            is TokenRotationResult.Failure -> {
                NanoHTTPD.newFixedLengthResponse(
                    toStatus(result.statusCode),
                    JSON_UTF8,
                    "{\"status\":\"error\",\"reason\":${jsonString(result.reason)}}"
                )
            }
        }
    }

    private fun handleTokenRevokeEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }
        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val tokenId = valueOf(payload, "tokenId")?.trim().orEmpty()
        if (tokenId.isBlank()) return badRequest("tokenId is required")

        return when (val result = runBlocking {
            securityManager.revokeToken(
                requesterToken = extractToken(session),
                requesterDeviceId = extractDeviceId(session),
                tokenIdToRevoke = tokenId
            )
        }) {
            is TokenRevocationResult.Success -> {
                val body = "{\"status\":\"ok\",\"revokedTokenId\":${jsonString(result.revokedTokenId)}}"
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
            }
            is TokenRevocationResult.Failure -> {
                NanoHTTPD.newFixedLengthResponse(
                    toStatus(result.statusCode),
                    JSON_UTF8,
                    "{\"status\":\"error\",\"reason\":${jsonString(result.reason)}}"
                )
            }
        }
    }

    private fun handleTorchEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val enabled = parseBoolean(valueOf(payload, "enabled"))
            ?: parseActionToEnabled(valueOf(payload, "action"))
            ?: return badRequest("missing enabled/action. expected enabled=true|false or action=start|stop")

        val result = runBlocking { cameraControlManager.setTorch(enabled) }
        return cameraControlResponse(result)
    }

    private fun handleNightModeEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val enabled = parseBoolean(valueOf(payload, "enabled"))
            ?: parseActionToEnabled(valueOf(payload, "action"))
            ?: return badRequest("missing enabled/action. expected enabled=true|false or action=start|stop")

        val result = runBlocking { cameraControlManager.setNightMode(enabled) }
        return cameraControlResponse(result)
    }

    private fun handleAudioLiveEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val modeRaw = valueOf(payload, "mode", "type")
            ?: return badRequest("missing mode. expected: ptt/live")
        val mode = when (modeRaw.trim().lowercase()) {
            "ptt", "push-to-talk", "push_to_talk" -> AudioLiveMode.PUSH_TO_TALK
            "live", "listen", "monitor", "live_monitor" -> AudioLiveMode.LIVE_MONITOR
            else -> null
        } ?: return badRequest("invalid mode. expected: ptt/live")

        val enabled = parseBoolean(valueOf(payload, "enabled"))
            ?: parseActionToEnabled(valueOf(payload, "action"))
            ?: return badRequest("missing enabled/action. expected enabled=true|false or action=start|stop")

        val result = runBlocking { pushToTalkManager.handleLiveMode(mode, enabled) }
        return audioCommandResponse(result)
    }

    private fun cameraControlResponse(result: CameraControlCommandResult): NanoHTTPD.Response {
        val body = when (result) {
            is CameraControlCommandResult.Success -> buildCameraControlResponseJson(
                status = "ok",
                code = null,
                message = result.message,
                snapshot = result.snapshot
            )
            is CameraControlCommandResult.Failure -> buildCameraControlResponseJson(
                status = "error",
                code = result.code.name.lowercase(),
                message = result.message,
                snapshot = result.snapshot
            )
        }

        val status = when (result) {
            is CameraControlCommandResult.Success -> NanoHTTPD.Response.Status.OK
            is CameraControlCommandResult.Failure -> when (result.code) {
                CameraControlErrorCode.ENGINE_NOT_READY -> NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE
                CameraControlErrorCode.UNSUPPORTED,
                CameraControlErrorCode.CONFLICT -> NanoHTTPD.Response.Status.CONFLICT
                CameraControlErrorCode.INTERNAL_ERROR -> NanoHTTPD.Response.Status.INTERNAL_ERROR
            }
        }

        if (result is CameraControlCommandResult.Failure) {
            logger.w(
                LogEventId.COMPONENT_FAILED,
                "Camera control rejected code=${result.code.name} message=${result.message}"
            )
        }

        return NanoHTTPD.newFixedLengthResponse(status, JSON_UTF8, body)
    }

    private fun handleAudioPlayEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val clipId = valueOf(payload, "clipId", "clip", "id", "soundId")
            ?.trim()
            ?: return badRequest("missing clipId")

        val category = when (valueOf(payload, "category")?.trim()?.lowercase()) {
            "aversive", "alert", "deterrent" -> AudioPlaybackCategory.AVERSIVE
            "standard", "normal", null, "" -> {
                if (parseBoolean(valueOf(payload, "aversive")) == true) {
                    AudioPlaybackCategory.AVERSIVE
                } else {
                    AudioPlaybackCategory.STANDARD
                }
            }
            else -> return badRequest("invalid category. expected: standard/aversive")
        }

        val result = runBlocking {
            pushToTalkManager.playStoredAudio(
                AudioPlaybackRequest(
                    clipId = clipId,
                    category = category
                )
            )
        }
        return audioCommandResponse(result)
    }

    private fun handleVolumeEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.POST) {
            return methodNotAllowed("POST")
        }

        val payload = parsePostPayload(session) ?: return badRequest("request body is required")
        val level = valueOf(payload, "level", "volume")
            ?.toIntOrNull()
            ?: return badRequest("missing level. expected integer value")

        val result = runBlocking { pushToTalkManager.setVolume(level) }
        return audioCommandResponse(result)
    }

    private fun handleRecordingsListEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET) {
            return methodNotAllowed("GET")
        }
        val rawFrom = firstQueryValue(session, "fromEpochMs")
            ?: firstQueryValue(session, "from")
            ?: firstQueryValue(session, "start")
        val fromEpochMs = parseEpochParam(rawFrom)
        if (rawFrom != null && fromEpochMs == null) {
            return badRequest("invalid fromEpochMs/from/start")
        }

        val rawTo = firstQueryValue(session, "toEpochMs")
            ?: firstQueryValue(session, "to")
            ?: firstQueryValue(session, "end")
        val toEpochMs = parseEpochParam(rawTo)
        if (rawTo != null && toEpochMs == null) {
            return badRequest("invalid toEpochMs/to/end")
        }

        if (fromEpochMs != null && toEpochMs != null && fromEpochMs > toEpochMs) {
            return badRequest("from must be <= to")
        }
        val limit = (firstQueryValue(session, "limit")?.toIntOrNull() ?: DEFAULT_RECORDINGS_LIST_LIMIT)
            .coerceIn(1, MAX_RECORDINGS_LIST_LIMIT)

        val recordings = runBlocking {
            loopRecordingManager.queryRecordings(
                fromEpochMs = fromEpochMs,
                toEpochMs = toEpochMs,
                limit = limit
            )
        }

        val body = buildString(capacity = 512 + recordings.size * 128) {
            append('{')
            append("\"status\":\"ok\",")
            append("\"count\":").append(recordings.size).append(',')
            append("\"recordings\":[")
            recordings.forEachIndexed { index, clip ->
                if (index > 0) append(',')
                append(recordingClipJson(clip))
            }
            append("]")
            append('}')
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
    }

    private fun handleRecordingEventsEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET) {
            return methodNotAllowed("GET")
        }
        val rawFrom = firstQueryValue(session, "fromEpochMs")
            ?: firstQueryValue(session, "from")
            ?: firstQueryValue(session, "start")
        val fromEpochMs = parseEpochParam(rawFrom)
        if (rawFrom != null && fromEpochMs == null) {
            return badRequest("invalid fromEpochMs/from/start")
        }

        val rawTo = firstQueryValue(session, "toEpochMs")
            ?: firstQueryValue(session, "to")
            ?: firstQueryValue(session, "end")
        val toEpochMs = parseEpochParam(rawTo)
        if (rawTo != null && toEpochMs == null) {
            return badRequest("invalid toEpochMs/to/end")
        }

        if (fromEpochMs != null && toEpochMs != null && fromEpochMs > toEpochMs) {
            return badRequest("from must be <= to")
        }
        val limit = (firstQueryValue(session, "limit")?.toIntOrNull() ?: DEFAULT_RECORDINGS_LIST_LIMIT)
            .coerceIn(1, MAX_RECORDINGS_LIST_LIMIT * 2)

        val events = runBlocking {
            loopRecordingManager.queryRecordingEvents(
                fromEpochMs = fromEpochMs,
                toEpochMs = toEpochMs,
                limit = limit
            )
        }

        val body = buildString(capacity = 256 + events.size * 96) {
            append('{')
            append("\"status\":\"ok\",")
            append("\"count\":").append(events.size).append(',')
            append("\"events\":[")
            events.forEachIndexed { index, event ->
                if (index > 0) append(',')
                append(recordingEventJson(event))
            }
            append("]}")
        }
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
    }

    private fun handleRecordingDownloadEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET && session.method != NanoHTTPD.Method.HEAD) {
            return methodNotAllowed("GET, HEAD")
        }
        val prefix = "/api/recordings/"
        val encodedName = session.uri.orEmpty().removePrefix(prefix)
        if (encodedName.isBlank()) return badRequest("recording file name is required")
        val fileName = decodeComponent(encodedName).substringBefore('/')
        if (fileName.isBlank()) return badRequest("recording file name is required")

        val file = runBlocking { loopRecordingManager.resolveRecordingFile(fileName) }
            ?: return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.NOT_FOUND,
                JSON_UTF8,
                "{\"status\":\"error\",\"reason\":\"recording_not_found\"}"
            )

        val fileLength = file.length().coerceAtLeast(0L)
        val rawRangeHeader = session.headers["range"]
        val requestedRange = parseByteRange(rawRangeHeader, fileLength)
        if (!rawRangeHeader.isNullOrBlank() && requestedRange == null) {
            return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                JSON_UTF8,
                "{\"status\":\"error\",\"reason\":\"invalid_range\"}"
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Range", "bytes */$fileLength")
            }
        }

        return runCatching {
            val (responseStatus, startOffset, contentLength, contentRangeHeader) = if (requestedRange == null) {
                RecordingHttpRange(
                    status = NanoHTTPD.Response.Status.OK,
                    startOffset = 0L,
                    contentLength = fileLength,
                    contentRangeHeader = null
                )
            } else {
                val boundedStart = requestedRange.first.coerceIn(0L, (fileLength - 1L).coerceAtLeast(0L))
                val boundedEnd = requestedRange.last.coerceIn(boundedStart, (fileLength - 1L).coerceAtLeast(0L))
                RecordingHttpRange(
                    status = NanoHTTPD.Response.Status.PARTIAL_CONTENT,
                    startOffset = boundedStart,
                    contentLength = (boundedEnd - boundedStart + 1L).coerceAtLeast(0L),
                    contentRangeHeader = "bytes $boundedStart-$boundedEnd/$fileLength"
                )
            }
            val inputStream = file.inputStream().apply {
                skipExactly(startOffset)
            }
            NanoHTTPD.newFixedLengthResponse(
                responseStatus,
                "video/mp4",
                inputStream,
                contentLength
            ).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("Content-Disposition", "inline; filename=\"${file.name}\"")
                addHeader("Accept-Ranges", "bytes")
                addHeader("Content-Length", contentLength.toString())
                contentRangeHeader?.let { addHeader("Content-Range", it) }
            }
        }.getOrElse { error ->
            logger.e(LogEventId.COMPONENT_FAILED, error, "Failed to stream recording ${file.name}")
            NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.INTERNAL_ERROR,
                JSON_UTF8,
                "{\"status\":\"error\",\"reason\":\"recording_stream_failed\"}"
            )
        }
    }

    private fun audioCommandResponse(result: AudioCommandResult): NanoHTTPD.Response {
        val body = when (result) {
            is AudioCommandResult.Success -> buildAudioResponseJson(
                status = "ok",
                code = null,
                message = result.message,
                state = result.state
            )
            is AudioCommandResult.Failure -> buildAudioResponseJson(
                status = "error",
                code = result.code.name.lowercase(),
                message = result.message,
                state = result.state
            )
        }

        val status = when (result) {
            is AudioCommandResult.Success -> NanoHTTPD.Response.Status.OK
            is AudioCommandResult.Failure -> when (result.code) {
                AudioCommandErrorCode.ENGINE_NOT_READY -> NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE
                AudioCommandErrorCode.INVALID_ARGUMENT -> NanoHTTPD.Response.Status.BAD_REQUEST
                AudioCommandErrorCode.CONFLICT -> NanoHTTPD.Response.Status.CONFLICT
                AudioCommandErrorCode.COOLDOWN_ACTIVE -> TOO_MANY_REQUESTS_STATUS
                AudioCommandErrorCode.SYSTEM_VOLUME_UNAVAILABLE -> NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE
            }
        }

        if (result is AudioCommandResult.Failure) {
            logger.w(
                LogEventId.COMPONENT_FAILED,
                "Audio command rejected code=${result.code.name} message=${result.message}"
            )
        }

        return NanoHTTPD.newFixedLengthResponse(status, JSON_UTF8, body)
    }

    private fun parseByteRange(headerValue: String?, fileLength: Long): LongRange? {
        if (headerValue.isNullOrBlank()) return null
        if (!headerValue.startsWith("bytes=", ignoreCase = true)) return null
        if (fileLength <= 0L) return null

        val rawRange = headerValue.removePrefix("bytes=").substringBefore(',').trim()
        val startPart = rawRange.substringBefore('-', missingDelimiterValue = "").trim()
        val endPart = rawRange.substringAfter('-', missingDelimiterValue = "").trim()
        if (startPart.isEmpty() && endPart.isEmpty()) return null

        return when {
            startPart.isNotEmpty() -> {
                val start = startPart.toLongOrNull() ?: return null
                val end = if (endPart.isNotEmpty()) {
                    endPart.toLongOrNull() ?: return null
                } else {
                    fileLength - 1L
                }
                if (start < 0L || start >= fileLength || end < start) return null
                start..end
            }

            else -> {
                val suffixLength = endPart.toLongOrNull() ?: return null
                if (suffixLength <= 0L) return null
                val clampedLength = suffixLength.coerceAtMost(fileLength)
                val start = (fileLength - clampedLength).coerceAtLeast(0L)
                start..(fileLength - 1L)
            }
        }
    }

    private fun InputStream.skipExactly(byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            if (read() == -1) {
                throw IllegalStateException("Unexpected EOF while skipping $byteCount bytes")
            }
            remaining -= 1L
        }
    }

    private fun parsePostPayload(session: NanoHTTPD.IHTTPSession): Map<String, String>? {
        return runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"].orEmpty()
            val bodyFields = parseBodyFields(body)
            val paramFields = session.parameters
                .mapValues { (_, values) -> values.firstOrNull().orEmpty() }
            (paramFields + bodyFields).filterValues { it.isNotBlank() }
        }.onFailure { error ->
            logger.w(LogEventId.COMPONENT_FAILED, "Failed to parse request payload: ${error.message}")
        }.getOrNull()
    }

    private fun parseBodyFields(rawBody: String): Map<String, String> {
        val trimmed = rawBody.trim()
        if (trimmed.isBlank()) return emptyMap()

        val json = parseFlatJson(trimmed)
        if (json.isNotEmpty()) return json

        return parseFormUrlEncoded(trimmed)
    }

    private fun parseFlatJson(raw: String): Map<String, String> {
        if (!raw.startsWith("{") || !raw.endsWith("}")) return emptyMap()

        val matches = JSON_PAIR_REGEX.findAll(raw).toList()
        if (matches.isEmpty()) return emptyMap()

        return buildMap {
            matches.forEach { match ->
                val key = match.groups[1]?.value ?: return@forEach
                val rawValue = match.groups[2]?.value?.trim().orEmpty()
                val normalizedValue = when {
                    rawValue.startsWith('"') && rawValue.endsWith('"') && rawValue.length >= 2 -> {
                        unescapeJson(rawValue.substring(1, rawValue.length - 1))
                    }
                    rawValue.equals("null", ignoreCase = true) -> ""
                    else -> rawValue
                }
                put(key, normalizedValue)
            }
        }
    }

    private fun parseFormUrlEncoded(raw: String): Map<String, String> {
        return raw.split('&')
            .mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = decodeComponent(pair.substring(0, separator))
                val value = decodeComponent(pair.substring(separator + 1))
                if (key.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun decodeComponent(value: String): String {
        return runCatching {
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
        }.getOrDefault(value)
    }

    private fun parseBoolean(value: String?): Boolean? {
        return when (value?.trim()?.lowercase()) {
            "1", "true", "yes", "on", "enabled", "enable", "start" -> true
            "0", "false", "no", "off", "disabled", "disable", "stop" -> false
            else -> null
        }
    }

    private fun parseActionToEnabled(action: String?): Boolean? {
        return when (action?.trim()?.lowercase()) {
            "start", "on", "enable", "enabled" -> true
            "stop", "off", "disable", "disabled" -> false
            else -> null
        }
    }

    private fun parsePairingClientType(value: String?): PairingClientType? {
        return when (value?.trim()?.lowercase()) {
            "android_app", "android", "app", "client" -> PairingClientType.ANDROID_APP
            "web_browser", "browser", "web" -> PairingClientType.WEB_BROWSER
            else -> null
        }
    }

    private fun parseAudioSocketRole(session: NanoHTTPD.IHTTPSession): AudioSocketRole {
        return when (firstQueryValue(session, "role")?.lowercase()) {
            "live", "listen", "monitor" -> AudioSocketRole.LIVE
            "ptt", "push-to-talk", "push_to_talk" -> AudioSocketRole.PTT
            else -> AudioSocketRole.UNKNOWN
        }
    }

    private fun buildAudioSocketConnectionId(session: NanoHTTPD.IHTTPSession): String {
        val deviceId = extractDeviceId(session)?.ifBlank { null } ?: "anonymous"
        val remoteIp = session.remoteIpAddress.ifBlank { "unknown" }
        return "audio-$deviceId-$remoteIp-${System.nanoTime()}"
    }

    private fun audioSocketConfigJson(
        config: AudioTransportConfig,
        role: AudioSocketRole
    ): String {
        return buildString(capacity = 160) {
            append('{')
            append("\"type\":\"config\",")
            append("\"role\":").append(jsonString(role.wireName)).append(',')
            append("\"sampleRateHz\":").append(config.sampleRateHz).append(',')
            append("\"channelCount\":").append(config.channelCount).append(',')
            append("\"bytesPerSample\":").append(config.bytesPerSample).append(',')
            append("\"frameDurationMs\":").append(config.frameDurationMs).append(',')
            append("\"frameBytes\":").append(config.frameBytes).append(',')
            append("\"encoding\":").append(jsonString(config.encoding))
            append('}')
        }
    }

    private fun parseEpochParam(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return value.toLongOrNull()?.takeIf { it >= 0L }
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun recordingClipJson(clip: RecordingClipSummary): String {
        return buildString(capacity = 192) {
            append('{')
            append("\"fileName\":").append(jsonString(clip.fileName)).append(',')
            append("\"startedAtEpochMs\":").append(clip.startedAtEpochMs).append(',')
            append("\"endedAtEpochMs\":").append(clip.endedAtEpochMs).append(',')
            append("\"durationMs\":").append(clip.durationMs).append(',')
            append("\"sizeBytes\":").append(clip.sizeBytes).append(',')
            append("\"container\":").append(jsonString(clip.container)).append(',')
            append("\"codec\":").append(jsonString(clip.codec))
            append('}')
        }
    }

    private fun recordingEventJson(event: RecordingEventSummary): String {
        return buildString(capacity = 128) {
            append('{')
            append("\"epochMs\":").append(event.epochMs).append(',')
            append("\"confidencePercent\":").append(event.confidencePercent).append(',')
            append("\"source\":").append(jsonString(event.source)).append(',')
            append("\"recordingFileName\":").append(jsonString(event.recordingFileName))
            append('}')
        }
    }

    private fun resolvePairingHost(hostHeader: String?): String {
        val normalized = hostHeader?.trim().orEmpty()
        if (normalized.isBlank()) {
            return fallbackPairingHost()
        }

        val host = normalized.substringBefore(':').trim()
        val port = normalized.substringAfter(':', "")
            .toIntOrNull()
            ?.takeIf { it in 1..65535 }
            ?: activePort

        if (isLoopbackHost(host)) {
            return fallbackPairingHost()
        }
        return "$host:$port"
    }

    private fun fallbackPairingHost(): String {
        val lanHost = runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress && address.isSiteLocalAddress) {
                        return@runCatching address.hostAddress
                    }
                }
            }
            null
        }.getOrNull()

        return if (lanHost.isNullOrBlank()) {
            "127.0.0.1:$activePort"
        } else {
            "$lanHost:$activePort"
        }
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.trim().lowercase()
        return normalized == "127.0.0.1" ||
            normalized == "localhost" ||
            normalized == "0.0.0.0" ||
            normalized == "::1"
    }

    private fun extractToken(session: NanoHTTPD.IHTTPSession): String? {
        val headerLookup = session.headers.entries.associate { it.key.lowercase() to it.value }
        val bearer = headerLookup["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
        val queryToken = firstQueryValue(session, "token")
            ?: firstQueryValue(session, "x-zcam-token")
            ?: firstQueryValue(session, "apiToken")
        val token = bearer ?: headerLookup["x-zcam-token"] ?: headerLookup["x-api-token"] ?: queryToken
        return token?.trim()?.ifBlank { null }
    }

    private fun extractDeviceId(session: NanoHTTPD.IHTTPSession): String? {
        val headerLookup = session.headers.entries.associate { it.key.lowercase() to it.value }
        val queryDeviceId = firstQueryValue(session, "deviceId")
            ?: firstQueryValue(session, "x-zcam-device-id")
        return (headerLookup["x-zcam-device-id"] ?: headerLookup["x-device-id"] ?: queryDeviceId)
            ?.trim()
            ?.ifBlank { null }
    }

    private fun firstQueryValue(session: NanoHTTPD.IHTTPSession, key: String): String? {
        return session.parameters[key]
            ?.firstOrNull()
            ?.trim()
            ?.ifBlank { null }
    }

    private fun isPublicPairingEndpoint(uri: String, method: NanoHTTPD.Method): Boolean {
        if (uri == "/api/security/pair/qr" && method == NanoHTTPD.Method.GET) return true
        if (uri == "/api/security/pair/request" && method == NanoHTTPD.Method.POST) return true
        if (uri == "/api/security/pair/complete" && method == NanoHTTPD.Method.POST) return true
        if (uri == "/api/security/pair" && method == NanoHTTPD.Method.POST) return true
        return false
    }

    private fun isPublicEndpoint(uri: String, method: NanoHTTPD.Method): Boolean {
        if (uri == "/") return true
        if (uri == "/health") return true
        if (isPublicPairingEndpoint(uri, method)) return true
        return false
    }

    private fun toStatus(code: Int): NanoHTTPD.Response.IStatus {
        return when (code) {
            200 -> NanoHTTPD.Response.Status.OK
            400 -> NanoHTTPD.Response.Status.BAD_REQUEST
            401 -> NanoHTTPD.Response.Status.UNAUTHORIZED
            403 -> NanoHTTPD.Response.Status.FORBIDDEN
            404 -> NanoHTTPD.Response.Status.NOT_FOUND
            409 -> NanoHTTPD.Response.Status.CONFLICT
            429 -> TOO_MANY_REQUESTS_STATUS
            503 -> NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE
            else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
        }
    }

    private fun valueOf(values: Map<String, String>, vararg keys: String): String? {
        val lookup = values.entries.associate { it.key.lowercase() to it.value }
        keys.forEach { key ->
            lookup[key.lowercase()]?.let { return it }
        }
        return null
    }

    private fun buildVideoResponse(): NanoHTTPD.Response {
        val stream = MjpegMultipartInputStream(
            frameSource = frameSource,
            boundary = BOUNDARY,
            targetFps = frameStatusSource.snapshot().targetFps,
            onOpen = {
                activeStreamClients.incrementAndGet()
            },
            onClose = {
                decrementActiveClients()
            }
        )

        return NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "multipart/x-mixed-replace; boundary=$BOUNDARY",
            stream
        ).apply {
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            addHeader("Pragma", "no-cache")
            addHeader("Connection", "close")
        }
    }

    private fun buildSnapshotResponse(): NanoHTTPD.Response {
        val frame = frameSource.latestFrame()
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(frame),
            frame.size.toLong()
        ).apply {
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            addHeader("Pragma", "no-cache")
        }
    }

    private fun buildStatusResponse(): NanoHTTPD.Response {
        val body = buildStatusJson()
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            JSON_UTF8,
            body
        ).apply {
            addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
            addHeader("Pragma", "no-cache")
        }
    }

    private fun buildPanelResponse(): NanoHTTPD.Response {
        val html = buildPanelHtml()
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            "text/html; charset=utf-8",
            html
        )
    }

    private fun buildStatusJson(): String {
        val now = System.currentTimeMillis()
        val uptimeMs = (now - startedAtEpochMs.get()).coerceAtLeast(0L)
        val frame = frameStatusSource.snapshot()
        val cameraControls = cameraControlManager.controlsSnapshot()
        val lastFrameAgeMs = if (frame.lastFrameEpochMs > 0L) {
            (now - frame.lastFrameEpochMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        val audio = pushToTalkManager.snapshotState()
        val power = powerStatusProvider.snapshot()

        return buildString(capacity = 768) {
            append('{')
            append("\"status\":\"ok\",")
            append("\"server\":{")
            append("\"port\":").append(activePort).append(',')
            append("\"alive\":").append(server?.isAlive == true).append(',')
            append("\"uptimeMs\":").append(uptimeMs).append(',')
            append("\"streamClients\":").append(activeStreamClients.get())
            append("},")
            append("\"video\":{")
            append("\"running\":").append(frame.running).append(',')
            append("\"targetWidth\":").append(frame.targetWidth).append(',')
            append("\"targetHeight\":").append(frame.targetHeight).append(',')
            append("\"targetFps\":").append(frame.targetFps).append(',')
            append("\"producedFrames\":").append(frame.producedFrames).append(',')
            append("\"droppedFrames\":").append(frame.droppedFrames).append(',')
            append("\"lastFrameEpochMs\":").append(frame.lastFrameEpochMs).append(',')
            append("\"lastFrameAgeMs\":").append(lastFrameAgeMs)
            append("},")
            append("\"cameraControls\":").append(cameraControlStateJson(cameraControls)).append(',')
            append("\"audio\":").append(audioStateJson(audio)).append(',')
            append("\"power\":").append(powerStateJson(power))
            append('}')
        }
    }

    private fun buildCameraControlResponseJson(
        status: String,
        code: String?,
        message: String,
        snapshot: com.zcam.camera.CameraControlsSnapshot
    ): String {
        return buildString(capacity = 384) {
            append('{')
            append("\"status\":\"").append(status).append("\",")
            append("\"code\":").append(jsonString(code)).append(',')
            append("\"message\":").append(jsonString(message)).append(',')
            append("\"cameraControls\":").append(cameraControlStateJson(snapshot))
            append('}')
        }
    }

    private fun buildAudioResponseJson(
        status: String,
        code: String?,
        message: String,
        state: com.zcam.audio.AudioStateSnapshot
    ): String {
        return buildString(capacity = 512) {
            append('{')
            append("\"status\":\"").append(status).append("\",")
            append("\"code\":").append(jsonString(code)).append(',')
            append("\"message\":").append(jsonString(message)).append(',')
            append("\"audio\":").append(audioStateJson(state))
            append('}')
        }
    }

    private fun audioStateJson(state: com.zcam.audio.AudioStateSnapshot): String {
        return buildString(capacity = 320) {
            append('{')
            append("\"engineStarted\":").append(state.engineStarted).append(',')
            append("\"transmitting\":").append(state.transmitting).append(',')
            append("\"liveListening\":").append(state.liveListening).append(',')
            append("\"playingBack\":").append(state.playingBack).append(',')
            append("\"activeClipId\":").append(jsonString(state.activeClipId)).append(',')
            append("\"volumePercent\":").append(state.volumePercent).append(',')
            append("\"minVolumePercent\":").append(state.minVolumePercent).append(',')
            append("\"maxVolumePercent\":").append(state.maxVolumePercent).append(',')
            append("\"aversiveCooldownMs\":").append(state.aversiveCooldownMs).append(',')
            append("\"aversiveCooldownRemainingMs\":").append(state.aversiveCooldownRemainingMs)
            append('}')
        }
    }

    private fun cameraControlStateJson(state: com.zcam.camera.CameraControlsSnapshot): String {
        return buildString(capacity = 192) {
            append('{')
            append("\"running\":").append(state.running).append(',')
            append("\"torchEnabled\":").append(state.torchEnabled).append(',')
            append("\"nightModeEnabled\":").append(state.nightModeEnabled).append(',')
            append("\"lowLightBoostSupported\":").append(state.lowLightBoostSupported).append(',')
            append("\"lastError\":").append(jsonString(state.lastError))
            append('}')
        }
    }

    private fun powerStateJson(state: PowerStatusSnapshot): String {
        return buildString(capacity = 96) {
            append('{')
            append("\"batteryPercent\":").append(state.batteryPercent?.toString() ?: "null").append(',')
            append("\"charging\":").append(state.charging?.toString() ?: "null")
            append('}')
        }
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        return "\"${escapeJson(value)}\""
    }

    private fun escapeJson(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun unescapeJson(value: String): String {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun methodNotAllowed(allow: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED,
            NanoHTTPD.MIME_PLAINTEXT,
            "method not allowed"
        ).apply {
            addHeader("Allow", allow)
        }
    }

    private fun badRequest(message: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            JSON_UTF8,
            "{\"status\":\"error\",\"message\":${jsonString(message)}}"
        )
    }

    private fun buildPanelHtml(): String {
        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>ZCam Local Panel</title>
              <style>
                :root {
                  --bg: #0f1115;
                  --card: #171a22;
                  --card2: #11141b;
                  --text: #e8ecf4;
                  --muted: #9aa5b1;
                  --accent: #2f8cff;
                  --ok: #22c55e;
                  --warn: #f59e0b;
                  --err: #ef4444;
                  --border: #2a3140;
                }
                * { box-sizing: border-box; }
                body { margin: 0; font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif; background: var(--bg); color: var(--text); }
                .wrap { max-width: 1080px; margin: 0 auto; padding: 14px; display: grid; gap: 12px; }
                .card { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 12px; }
                .row { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
                .grid2 { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 10px; }
                .grid3 { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 8px; }
                .title { font-size: 18px; font-weight: 700; margin: 0 0 8px 0; }
                .sub { color: var(--muted); font-size: 13px; margin: 0 0 10px 0; }
                label { font-size: 12px; color: var(--muted); display: block; margin-bottom: 4px; }
                input, select, button { border-radius: 8px; border: 1px solid var(--border); background: var(--card2); color: var(--text); padding: 8px 10px; font-size: 14px; }
                input[type=range] { padding: 0; }
                button { cursor: pointer; }
                button.primary { background: var(--accent); border-color: var(--accent); color: white; font-weight: 600; }
                button.ok { border-color: #1f7a3f; }
                button.warn { border-color: #8a5e00; }
                button.err { border-color: #8a2424; }
                .codeInput { max-width: 180px; letter-spacing: 3px; }
                img, video { width: 100%; height: auto; border-radius: 8px; background: #000; border: 1px solid var(--border); }
                pre { margin: 0; background: var(--card2); border: 1px solid var(--border); border-radius: 8px; padding: 10px; max-height: 260px; overflow: auto; white-space: pre-wrap; word-break: break-word; font-size: 12px; }
                .chip { display: inline-block; border-radius: 999px; border: 1px solid var(--border); padding: 3px 8px; font-size: 12px; color: var(--muted); }
                .stack { display: grid; gap: 8px; }
                .list { display: grid; gap: 6px; max-height: 320px; overflow: auto; }
                .recordingItem { width: 100%; text-align: left; }
                .okText { color: var(--ok); }
                .warnText { color: var(--warn); }
                .errText { color: var(--err); }
                .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="card">
                  <h1 class="title">ZCam Local Panel</h1>
                  <p class="sub">Preferred pairing flow: request pairing from this browser, read the 6-digit code on the server app, then this browser stores its trusted token automatically. Manual token entry remains available for recovery.</p>
                  <div class="grid3">
                    <div>
                      <label for="tokenInput">Trusted token</label>
                      <input id="tokenInput" type="text" placeholder="zcam_..." />
                    </div>
                    <div>
                      <label for="deviceIdInput">Device ID</label>
                      <input id="deviceIdInput" type="text" placeholder="browser-console" />
                    </div>
                    <div>
                      <label for="browserNameInput">Browser display name</label>
                      <input id="browserNameInput" type="text" placeholder="Web Browser" />
                    </div>
                  </div>
                  <div class="row" style="margin-top:10px;">
                    <button id="requestPairingBtn" class="primary" type="button">Request pairing</button>
                    <input id="pairingCodeInput" class="codeInput mono" type="text" inputmode="numeric" maxlength="6" placeholder="123456" />
                    <button id="completePairingBtn" class="ok" type="button">Complete pairing</button>
                    <button id="applyAuthBtn" type="button">Save auth</button>
                    <button id="clearAuthBtn" type="button">Clear token</button>
                  </div>
                  <div class="row" style="margin-top:8px;">
                    <span class="chip mono" id="authState">auth: not set</span>
                    <span class="chip mono" id="pairingState">pairing: idle</span>
                    <span class="chip mono" id="statusLine">status: unknown</span>
                  </div>
                </div>

                <div class="card">
                  <h2 class="title">Browser Pairing</h2>
                  <p class="sub">1. Open the server device pairing screen. 2. Click Request pairing here. 3. Enter the code shown on the server. 4. This browser keeps the trusted token in local storage.</p>
                  <div class="grid3">
                    <div>
                      <label for="pairingRequestId">Pending request ID</label>
                      <input id="pairingRequestId" type="text" readonly />
                    </div>
                    <div>
                      <label for="pairingExpiresAt">Request expires</label>
                      <input id="pairingExpiresAt" type="text" readonly />
                    </div>
                    <div>
                      <label>&nbsp;</label>
                      <div class="sub">If pairing is retried, a new request replaces the old one for this browser device ID.</div>
                    </div>
                  </div>
                </div>

                <div class="grid2">
                  <div class="card">
                    <h2 class="title">Video</h2>
                    <p class="sub">Preview stream uses query auth. For best stability keep one viewer open.</p>
                    <div class="row" style="margin-bottom:8px;">
                      <button id="reloadPreviewBtn" type="button">Reload preview</button>
                      <a id="snapshotLink" class="chip" href="/snapshot.jpg" target="_blank">Open snapshot</a>
                    </div>
                    <img id="videoPreview" src="/video" alt="ZCam stream" />
                  </div>

                  <div class="card">
                    <h2 class="title">Camera Controls</h2>
                    <div class="row">
                      <button class="ok" type="button" onclick="setTorch(true)">Torch ON</button>
                      <button type="button" onclick="setTorch(false)">Torch OFF</button>
                    </div>
                    <div class="row" style="margin-top:8px;">
                      <button class="ok" type="button" onclick="setNightMode(true)">Night mode ON</button>
                      <button type="button" onclick="setNightMode(false)">Night mode OFF</button>
                    </div>
                    <p class="sub" style="margin-top:10px;">Night mode maps to CameraX low-light boost; may require FPS <= 30.</p>
                  </div>
                </div>

                <div class="grid2">
                  <div class="card">
                    <h2 class="title">Audio Live</h2>
                    <div class="row">
                      <button class="ok" type="button" onclick="setAudioLive('ptt', true)">PTT ON</button>
                      <button type="button" onclick="setAudioLive('ptt', false)">PTT OFF</button>
                    </div>
                    <div class="row" style="margin-top:8px;">
                      <button class="ok" type="button" onclick="setAudioLive('live', true)">Live listen ON</button>
                      <button type="button" onclick="setAudioLive('live', false)">Live listen OFF</button>
                    </div>
                    <div class="row" style="margin-top:8px;">
                      <span class="chip mono" id="audioSocketState">audio socket: idle</span>
                    </div>
                  </div>

                  <div class="card">
                    <h2 class="title">Playback & Volume</h2>
                    <div class="row">
                      <button type="button" onclick="playSound('alert_chime', false)">Alert</button>
                      <button type="button" onclick="playSound('door_knock', false)">Knock</button>
                      <button class="warn" type="button" onclick="playSound('deterrent_1', true)">Deterrent</button>
                    </div>
                    <div class="row" style="margin-top:10px;">
                      <input id="volumeRange" type="range" min="0" max="85" value="40" />
                      <span id="volumeLabel" class="mono">40%</span>
                      <button type="button" onclick="setVolumeNow()">Set volume</button>
                    </div>
                  </div>
                </div>

                <div class="card">
                  <h2 class="title">Recordings</h2>
                  <p class="sub">Browse loop recordings directly in the browser. Event markers jump to the closest clip timestamp when metadata is available.</p>
                  <div class="grid3">
                    <div>
                      <label for="recordingsFromInput">From</label>
                      <input id="recordingsFromInput" type="datetime-local" />
                    </div>
                    <div>
                      <label for="recordingsToInput">To</label>
                      <input id="recordingsToInput" type="datetime-local" />
                    </div>
                    <div>
                      <label>&nbsp;</label>
                      <div class="row">
                        <button id="loadRecordingsBtn" type="button">Load recordings</button>
                        <button id="loadRecentRecordingsBtn" type="button">Last 12h</button>
                      </div>
                    </div>
                  </div>
                  <div class="grid2" style="margin-top:10px;">
                    <div class="stack">
                      <video id="recordingPlayer" controls preload="metadata" playsinline></video>
                      <div class="row">
                        <span class="chip mono" id="recordingsState">recordings: idle</span>
                        <span class="chip mono" id="recordingSelection">selected: none</span>
                      </div>
                    </div>
                    <div class="stack">
                      <div>
                        <label>Recording list</label>
                        <div id="recordingsList" class="list"></div>
                      </div>
                      <div>
                        <label>Event markers</label>
                        <div id="recordingsEvents" class="list"></div>
                      </div>
                    </div>
                  </div>
                </div>

                <div class="card">
                  <h2 class="title">Status JSON</h2>
                  <pre id="statusJson">loading...</pre>
                </div>

                <div class="card">
                  <h2 class="title">API Log</h2>
                  <pre id="apiLog">ready</pre>
                </div>
              </div>
              <script>
                const tokenInput = document.getElementById('tokenInput');
                const deviceIdInput = document.getElementById('deviceIdInput');
                const browserNameInput = document.getElementById('browserNameInput');
                const pairingCodeInput = document.getElementById('pairingCodeInput');
                const pairingRequestIdInput = document.getElementById('pairingRequestId');
                const pairingExpiresAtInput = document.getElementById('pairingExpiresAt');
                const authState = document.getElementById('authState');
                const pairingState = document.getElementById('pairingState');
                const statusLine = document.getElementById('statusLine');
                const statusJson = document.getElementById('statusJson');
                const apiLog = document.getElementById('apiLog');
                const videoPreview = document.getElementById('videoPreview');
                const snapshotLink = document.getElementById('snapshotLink');
                const volumeRange = document.getElementById('volumeRange');
                const volumeLabel = document.getElementById('volumeLabel');
                const audioSocketState = document.getElementById('audioSocketState');
                const recordingsFromInput = document.getElementById('recordingsFromInput');
                const recordingsToInput = document.getElementById('recordingsToInput');
                const loadRecordingsBtn = document.getElementById('loadRecordingsBtn');
                const loadRecentRecordingsBtn = document.getElementById('loadRecentRecordingsBtn');
                const recordingsState = document.getElementById('recordingsState');
                const recordingSelection = document.getElementById('recordingSelection');
                const recordingsList = document.getElementById('recordingsList');
                const recordingsEvents = document.getElementById('recordingsEvents');
                const recordingPlayer = document.getElementById('recordingPlayer');
                const STORAGE = {
                  token: 'zcam.panel.token',
                  deviceId: 'zcam.panel.deviceId',
                  browserName: 'zcam.panel.browserName',
                  pendingRequestId: 'zcam.panel.pendingRequestId',
                  pendingExpiresAt: 'zcam.panel.pendingExpiresAt'
                };
                const DEFAULT_AUDIO_CONFIG = {
                  sampleRateHz: 16000,
                  channelCount: 1,
                  bytesPerSample: 2,
                  frameDurationMs: 20,
                  frameBytes: 640,
                  encoding: 'pcm_s16le'
                };
                let liveSocket = null;
                let pttSocket = null;
                let liveAudioContext = null;
                let liveAudioConfig = { ...DEFAULT_AUDIO_CONFIG };
                let pttAudioContext = null;
                let pttAudioConfig = { ...DEFAULT_AUDIO_CONFIG };
                let livePlaybackCursor = 0;
                let pttMediaStream = null;
                let pttProcessor = null;
                let pttSourceNode = null;
                let pttMuteNode = null;
                let recordingsCache = [];
                let recordingEventsCache = [];
                let selectedRecordingName = '';
                let pendingSeekSeconds = null;

                function readAuth() {
                  return {
                    token: tokenInput.value.trim(),
                    deviceId: deviceIdInput.value.trim()
                  };
                }

                function readPendingPairing() {
                  return {
                    requestId: localStorage.getItem(STORAGE.pendingRequestId) || '',
                    expiresAtEpochMs: Number(localStorage.getItem(STORAGE.pendingExpiresAt) || '0')
                  };
                }

                function saveAuthInputs() {
                  localStorage.setItem(STORAGE.token, tokenInput.value.trim());
                  localStorage.setItem(STORAGE.deviceId, deviceIdInput.value.trim());
                  localStorage.setItem(STORAGE.browserName, browserNameInput.value.trim());
                }

                function savePendingPairing(requestId, expiresAtEpochMs) {
                  localStorage.setItem(STORAGE.pendingRequestId, requestId || '');
                  localStorage.setItem(STORAGE.pendingExpiresAt, String(expiresAtEpochMs || 0));
                }

                function clearPendingPairing(clearCodeInput) {
                  localStorage.removeItem(STORAGE.pendingRequestId);
                  localStorage.removeItem(STORAGE.pendingExpiresAt);
                  if (clearCodeInput) pairingCodeInput.value = '';
                }

                function formatExpiry(epochMs) {
                  if (!Number.isFinite(epochMs) || epochMs <= 0) return '-';
                  return new Date(epochMs).toLocaleTimeString();
                }

                function generateBrowserDeviceId() {
                  const suffix = (window.crypto && typeof window.crypto.randomUUID === 'function')
                    ? window.crypto.randomUUID().replace(/[^A-Za-z0-9._:-]/g, '').slice(0, 24)
                    : Math.random().toString(36).slice(2, 12);
                  return 'browser-' + suffix;
                }

                function ensureBrowserIdentity() {
                  if (!deviceIdInput.value.trim()) {
                    deviceIdInput.value = localStorage.getItem(STORAGE.deviceId) || generateBrowserDeviceId();
                  }
                  if (!browserNameInput.value.trim()) {
                    browserNameInput.value = localStorage.getItem(STORAGE.browserName) || 'Web Browser';
                  }
                  saveAuthInputs();
                }

                function updateAuthState() {
                  const auth = readAuth();
                  const hasToken = !!auth.token;
                  authState.textContent = hasToken
                    ? 'auth: trusted token saved, device=' + (auth.deviceId || '-')
                    : 'auth: not set';
                  authState.className = 'chip mono ' + (hasToken ? 'okText' : 'warnText');
                }

                function updatePairingState() {
                  const pending = readPendingPairing();
                  if (pending.requestId && pending.expiresAtEpochMs > Date.now()) {
                    pairingRequestIdInput.value = pending.requestId;
                    pairingExpiresAtInput.value = formatExpiry(pending.expiresAtEpochMs);
                    pairingState.textContent = 'pairing: pending request ' + pending.requestId;
                    pairingState.className = 'chip mono warnText';
                    return;
                  }

                  if (pending.requestId) {
                    clearPendingPairing(false);
                  }
                  pairingRequestIdInput.value = '';
                  pairingExpiresAtInput.value = '';
                  if (readAuth().token) {
                    pairingState.textContent = 'pairing: trusted token active';
                    pairingState.className = 'chip mono okText';
                  } else {
                    pairingState.textContent = 'pairing: idle';
                    pairingState.className = 'chip mono warnText';
                  }
                }

                function authHeaders() {
                  const auth = readAuth();
                  const headers = { 'Accept': 'application/json' };
                  if (auth.token) headers['X-ZCam-Token'] = auth.token;
                  if (auth.deviceId) headers['X-ZCam-Device-Id'] = auth.deviceId;
                  return headers;
                }

                function authQuery() {
                  const auth = readAuth();
                  const query = new URLSearchParams();
                  if (auth.token) query.set('token', auth.token);
                  if (auth.deviceId) query.set('deviceId', auth.deviceId);
                  const serialized = query.toString();
                  return serialized ? ('?' + serialized) : '';
                }

                function applyPreviewSources() {
                  const query = authQuery();
                  videoPreview.src = '/video' + query;
                  snapshotLink.href = '/snapshot.jpg' + query;
                }

                function audioSocketUrl(role) {
                  const auth = readAuth();
                  const query = new URLSearchParams();
                  query.set('role', role);
                  if (auth.token) query.set('token', auth.token);
                  if (auth.deviceId) query.set('deviceId', auth.deviceId);
                  const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
                  return protocol + location.host + '${AUDIO_SOCKET_PATH}?' + query.toString();
                }

                function updateAudioSocketState(label, toneClass) {
                  audioSocketState.textContent = label;
                  audioSocketState.className = 'chip mono ' + (toneClass || '');
                }

                function parseAudioConfigMessage(raw) {
                  try {
                    const parsed = JSON.parse(raw);
                    return parsed && parsed.type === 'config' ? parsed : null;
                  } catch (_) {
                    return null;
                  }
                }

                async function ensureLiveAudioContext() {
                  if (!liveAudioContext) {
                    const AudioContextCtor = window.AudioContext || window.webkitAudioContext;
                    liveAudioContext = AudioContextCtor ? new AudioContextCtor() : null;
                  }
                  if (!liveAudioContext) {
                    return false;
                  }
                  if (liveAudioContext.state === 'suspended') {
                    await liveAudioContext.resume();
                  }
                  livePlaybackCursor = Math.max(livePlaybackCursor, liveAudioContext.currentTime);
                  return true;
                }

                function playLiveAudioFrame(arrayBuffer) {
                  if (!liveAudioContext) return;
                  const sampleCount = Math.floor(arrayBuffer.byteLength / 2);
                  if (sampleCount <= 0) return;
                  const dataView = new DataView(arrayBuffer);
                  const audioBuffer = liveAudioContext.createBuffer(1, sampleCount, liveAudioConfig.sampleRateHz || 16000);
                  const channel = audioBuffer.getChannelData(0);
                  for (let index = 0; index < sampleCount; index += 1) {
                    channel[index] = Math.max(-1, Math.min(1, dataView.getInt16(index * 2, true) / 32768));
                  }
                  const source = liveAudioContext.createBufferSource();
                  source.buffer = audioBuffer;
                  source.connect(liveAudioContext.destination);
                  const startAt = Math.max(liveAudioContext.currentTime + 0.02, livePlaybackCursor);
                  source.start(startAt);
                  livePlaybackCursor = startAt + audioBuffer.duration;
                }

                function downsampleToPcm16(inputSamples, inputSampleRate, targetSampleRate) {
                  if (!inputSamples || inputSamples.length === 0) {
                    return new ArrayBuffer(0);
                  }
                  const ratio = inputSampleRate / targetSampleRate;
                  const outputLength = Math.max(1, Math.round(inputSamples.length / ratio));
                  const output = new ArrayBuffer(outputLength * 2);
                  const view = new DataView(output);
                  let outputIndex = 0;
                  let inputIndex = 0;
                  while (outputIndex < outputLength) {
                    const nextIndex = Math.min(inputSamples.length, Math.round((outputIndex + 1) * ratio));
                    let sum = 0;
                    let count = 0;
                    while (inputIndex < nextIndex) {
                      sum += inputSamples[inputIndex];
                      inputIndex += 1;
                      count += 1;
                    }
                    const averaged = count > 0 ? (sum / count) : 0;
                    const clamped = Math.max(-1, Math.min(1, averaged));
                    view.setInt16(outputIndex * 2, clamped < 0 ? clamped * 32768 : clamped * 32767, true);
                    outputIndex += 1;
                  }
                  return output;
                }

                function stopBrowserLiveListenAudio() {
                  if (liveSocket) {
                    try { liveSocket.close(1000, 'live_stopped'); } catch (_) {}
                  }
                  liveSocket = null;
                  livePlaybackCursor = 0;
                  updateAudioSocketState('audio socket: idle', '');
                }

                async function startBrowserLiveListenAudio() {
                  if (!(await ensureLiveAudioContext())) {
                    return { ok: false, reason: 'browser_audio_output_unavailable' };
                  }
                  stopBrowserLiveListenAudio();
                  return await new Promise((resolve) => {
                    const socket = new WebSocket(audioSocketUrl('live'));
                    socket.binaryType = 'arraybuffer';
                    let settled = false;
                    socket.onopen = () => {
                      liveSocket = socket;
                      updateAudioSocketState('audio socket: live listen', 'okText');
                      if (!settled) {
                        settled = true;
                        resolve({ ok: true });
                      }
                    };
                    socket.onmessage = async (event) => {
                      if (typeof event.data === 'string') {
                        const config = parseAudioConfigMessage(event.data);
                        if (config) {
                          liveAudioConfig = { ...DEFAULT_AUDIO_CONFIG, ...config };
                        }
                        return;
                      }
                      await ensureLiveAudioContext();
                      playLiveAudioFrame(event.data);
                    };
                    socket.onerror = () => {
                      updateAudioSocketState('audio socket: live error', 'errText');
                      if (!settled) {
                        settled = true;
                        resolve({ ok: false, reason: 'live_socket_failed' });
                      }
                    };
                    socket.onclose = () => {
                      if (liveSocket === socket) {
                        liveSocket = null;
                        updateAudioSocketState('audio socket: idle', '');
                      }
                    };
                  });
                }

                function stopBrowserPushToTalkAudio() {
                  if (pttProcessor) {
                    try { pttProcessor.disconnect(); } catch (_) {}
                    pttProcessor.onaudioprocess = null;
                  }
                  if (pttSourceNode) {
                    try { pttSourceNode.disconnect(); } catch (_) {}
                  }
                  if (pttMuteNode) {
                    try { pttMuteNode.disconnect(); } catch (_) {}
                  }
                  if (pttMediaStream) {
                    pttMediaStream.getTracks().forEach((track) => track.stop());
                  }
                  if (pttAudioContext) {
                    try { pttAudioContext.close(); } catch (_) {}
                  }
                  if (pttSocket) {
                    try { pttSocket.close(1000, 'ptt_stopped'); } catch (_) {}
                  }
                  pttProcessor = null;
                  pttSourceNode = null;
                  pttMuteNode = null;
                  pttMediaStream = null;
                  pttAudioContext = null;
                  pttSocket = null;
                  updateAudioSocketState('audio socket: idle', '');
                }

                async function startBrowserPushToTalkAudio() {
                  if (!window.isSecureContext || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                    return { ok: false, reason: 'browser_microphone_requires_https_or_localhost' };
                  }

                  stopBrowserPushToTalkAudio();
                  const AudioContextCtor = window.AudioContext || window.webkitAudioContext;
                  if (!AudioContextCtor) {
                    return { ok: false, reason: 'browser_audio_input_unavailable' };
                  }

                  try {
                    pttMediaStream = await navigator.mediaDevices.getUserMedia({
                      audio: {
                        echoCancellation: true,
                        noiseSuppression: true,
                        autoGainControl: true
                      }
                    });
                  } catch (error) {
                    return { ok: false, reason: 'microphone_access_denied', detail: String(error) };
                  }

                  pttAudioContext = new AudioContextCtor();
                  await pttAudioContext.resume();

                  return await new Promise((resolve) => {
                    const socket = new WebSocket(audioSocketUrl('ptt'));
                    let settled = false;
                    socket.binaryType = 'arraybuffer';
                    socket.onopen = () => {
                      pttSocket = socket;
                      pttSourceNode = pttAudioContext.createMediaStreamSource(pttMediaStream);
                      pttProcessor = pttAudioContext.createScriptProcessor(2048, 1, 1);
                      pttMuteNode = pttAudioContext.createGain();
                      pttMuteNode.gain.value = 0;
                      pttProcessor.onaudioprocess = (event) => {
                        if (!pttSocket || pttSocket.readyState !== WebSocket.OPEN) return;
                        const channel = event.inputBuffer.getChannelData(0);
                        const payload = downsampleToPcm16(
                          channel,
                          pttAudioContext.sampleRate,
                          pttAudioConfig.sampleRateHz || 16000
                        );
                        if (payload.byteLength > 0) {
                          pttSocket.send(payload);
                        }
                      };
                      pttSourceNode.connect(pttProcessor);
                      pttProcessor.connect(pttMuteNode);
                      pttMuteNode.connect(pttAudioContext.destination);
                      updateAudioSocketState('audio socket: push-to-talk', 'okText');
                      if (!settled) {
                        settled = true;
                        resolve({ ok: true });
                      }
                    };
                    socket.onmessage = (event) => {
                      if (typeof event.data !== 'string') return;
                      const config = parseAudioConfigMessage(event.data);
                      if (config) {
                        pttAudioConfig = { ...DEFAULT_AUDIO_CONFIG, ...config };
                      }
                    };
                    socket.onerror = () => {
                      updateAudioSocketState('audio socket: ptt error', 'errText');
                      if (!settled) {
                        settled = true;
                        resolve({ ok: false, reason: 'ptt_socket_failed' });
                      }
                    };
                    socket.onclose = () => {
                      if (pttSocket === socket) {
                        stopBrowserPushToTalkAudio();
                      }
                    };
                  });
                }

                function localDateTimeValue(epochMs) {
                  const date = new Date(epochMs);
                  const pad = (value) => String(value).padStart(2, '0');
                  return date.getFullYear() + '-' +
                    pad(date.getMonth() + 1) + '-' +
                    pad(date.getDate()) + 'T' +
                    pad(date.getHours()) + ':' +
                    pad(date.getMinutes());
                }

                function parseLocalDateTimeInput(value) {
                  if (!value) return null;
                  const parsed = new Date(value);
                  const epochMs = parsed.getTime();
                  return Number.isFinite(epochMs) ? epochMs : null;
                }

                function setRecentRecordingsRange(hoursBack) {
                  const now = Date.now();
                  recordingsFromInput.value = localDateTimeValue(now - (hoursBack * 60 * 60 * 1000));
                  recordingsToInput.value = localDateTimeValue(now);
                }

                function buildAuthorizedUrl(path, extraParams) {
                  const params = new URLSearchParams();
                  const auth = readAuth();
                  if (auth.token) params.set('token', auth.token);
                  if (auth.deviceId) params.set('deviceId', auth.deviceId);
                  Object.entries(extraParams || {}).forEach(([key, value]) => {
                    if (value !== null && value !== undefined && value !== '') {
                      params.set(key, String(value));
                    }
                  });
                  const serialized = params.toString();
                  return serialized ? (path + '?' + serialized) : path;
                }

                function formatRecordingLabel(item) {
                  const started = new Date(item.startedAtEpochMs).toLocaleString();
                  const seconds = Math.round((item.durationMs || 0) / 1000);
                  const sizeMb = ((item.sizeBytes || 0) / (1024 * 1024)).toFixed(1);
                  return started + ' | ' + seconds + 's | ' + sizeMb + ' MB';
                }

                function selectRecording(fileName, seekSeconds) {
                  const item = recordingsCache.find((entry) => entry.fileName === fileName);
                  if (!item) return;
                  selectedRecordingName = fileName;
                  pendingSeekSeconds = Number.isFinite(seekSeconds) ? Math.max(0, seekSeconds) : null;
                  recordingSelection.textContent = 'selected: ' + fileName;
                  recordingPlayer.src = buildAuthorizedUrl('/api/recordings/' + encodeURIComponent(fileName), {});
                  recordingsState.textContent = 'recordings: ' + recordingsCache.length + ' clip(s)';
                  renderRecordings();
                }

                function jumpToRecordingEvent(eventItem) {
                  const directClip = eventItem.recordingFileName
                    ? recordingsCache.find((entry) => entry.fileName === eventItem.recordingFileName)
                    : null;
                  const targetClip = directClip || recordingsCache.find((entry) => (
                    eventItem.epochMs >= entry.startedAtEpochMs && eventItem.epochMs <= entry.endedAtEpochMs
                  ));
                  if (!targetClip) return;
                  const seekSeconds = Math.max(0, Math.floor((eventItem.epochMs - targetClip.startedAtEpochMs) / 1000));
                  selectRecording(targetClip.fileName, seekSeconds);
                }

                function renderRecordings() {
                  recordingsList.innerHTML = '';
                  if (recordingsCache.length === 0) {
                    recordingsList.innerHTML = '<div class="sub">No recordings in the selected range.</div>';
                  } else {
                    recordingsCache.forEach((item) => {
                      const button = document.createElement('button');
                      button.type = 'button';
                      button.className = 'recordingItem' + (selectedRecordingName === item.fileName ? ' primary' : '');
                      button.textContent = formatRecordingLabel(item);
                      button.addEventListener('click', () => selectRecording(item.fileName, null));
                      recordingsList.appendChild(button);
                    });
                  }

                  recordingsEvents.innerHTML = '';
                  if (recordingEventsCache.length === 0) {
                    recordingsEvents.innerHTML = '<div class="sub">No event markers in the selected range.</div>';
                  } else {
                    recordingEventsCache.forEach((eventItem) => {
                      const button = document.createElement('button');
                      button.type = 'button';
                      button.className = 'recordingItem';
                      button.textContent = new Date(eventItem.epochMs).toLocaleString() + ' | ' + eventItem.confidencePercent + '% | ' + eventItem.source;
                      button.addEventListener('click', () => jumpToRecordingEvent(eventItem));
                      recordingsEvents.appendChild(button);
                    });
                  }
                }

                async function loadRecordings() {
                  const fromEpochMs = parseLocalDateTimeInput(recordingsFromInput.value);
                  const toEpochMs = parseLocalDateTimeInput(recordingsToInput.value);
                  recordingsState.textContent = 'recordings: loading';
                  try {
                    const recordingsUrl = buildAuthorizedUrl('/api/recordings', {
                      fromEpochMs,
                      toEpochMs,
                      limit: 240
                    });
                    const eventsUrl = buildAuthorizedUrl('/api/recordings/events', {
                      fromEpochMs,
                      toEpochMs,
                      limit: 500
                    });
                    const [recordingsResponse, eventsResponse] = await Promise.all([
                      fetch(recordingsUrl, { headers: authHeaders(), cache: 'no-store' }),
                      fetch(eventsUrl, { headers: authHeaders(), cache: 'no-store' })
                    ]);
                    const recordingsPayload = await recordingsResponse.json();
                    const eventsPayload = await eventsResponse.json();
                    recordingsCache = Array.isArray(recordingsPayload.recordings) ? recordingsPayload.recordings : [];
                    recordingEventsCache = Array.isArray(eventsPayload.events) ? eventsPayload.events : [];
                    if (selectedRecordingName && !recordingsCache.some((item) => item.fileName === selectedRecordingName)) {
                      selectedRecordingName = '';
                      recordingSelection.textContent = 'selected: none';
                    }
                    recordingsState.textContent = 'recordings: ' + recordingsCache.length + ' clip(s), ' + recordingEventsCache.length + ' event(s)';
                    if (!selectedRecordingName && recordingsCache.length > 0) {
                      selectRecording(recordingsCache[0].fileName, null);
                    } else {
                      renderRecordings();
                    }
                  } catch (error) {
                    recordingsState.textContent = 'recordings: load failed';
                    appendLog('recordings load failed: ' + error);
                  }
                }

                function appendLog(line) {
                  const stamp = new Date().toISOString();
                  apiLog.textContent = '[' + stamp + '] ' + line + '\n' + apiLog.textContent;
                }

                async function apiPost(path, payload) {
                  const response = await fetch(path, {
                    method: 'POST',
                    headers: {
                      ...authHeaders(),
                      'Content-Type': 'application/json'
                    },
                    body: JSON.stringify(payload)
                  });
                  const text = await response.text();
                  let parsed = null;
                  try {
                    parsed = JSON.parse(text);
                  } catch (_) {}
                  appendLog(path + ' -> ' + response.status + ' ' + response.statusText + ' | ' + text.substring(0, 220));
                  return { response, parsed, text };
                }

                async function requestBrowserPairing() {
                  const deviceId = deviceIdInput.value.trim();
                  const displayName = browserNameInput.value.trim() || 'Web Browser';
                  if (!deviceId) {
                    appendLog('pairing request blocked: device ID is required');
                    return;
                  }

                  const { response, parsed } = await apiPost('/api/security/pair/request', {
                    deviceId,
                    displayName,
                    clientType: 'web_browser'
                  });
                  if (!response.ok || !parsed) {
                    updatePairingState();
                    return;
                  }

                  saveAuthInputs();
                  savePendingPairing(parsed.requestId || '', parsed.expiresAtEpochMs || 0);
                  pairingCodeInput.value = '';
                  updatePairingState();
                  pairingCodeInput.focus();
                }

                async function completeBrowserPairing() {
                  const pending = readPendingPairing();
                  if (!pending.requestId) {
                    appendLog('pairing completion blocked: request pairing first');
                    return;
                  }
                  const verificationCode = pairingCodeInput.value.replace(/[^0-9]/g, '').slice(0, 6);
                  pairingCodeInput.value = verificationCode;
                  if (verificationCode.length !== 6) {
                    appendLog('pairing completion blocked: 6-digit code required');
                    return;
                  }

                  const { response, parsed } = await apiPost('/api/security/pair/complete', {
                    requestId: pending.requestId,
                    verificationCode
                  });
                  if (!response.ok || !parsed) {
                    updatePairingState();
                    return;
                  }

                  tokenInput.value = parsed.token || '';
                  deviceIdInput.value = parsed.deviceId || deviceIdInput.value.trim();
                  saveAuthInputs();
                  clearPendingPairing(true);
                  updateAuthState();
                  updatePairingState();
                  applyPreviewSources();
                  await refreshStatus();
                }

                async function refreshStatus() {
                  try {
                    const response = await fetch('/api/status', {
                      headers: authHeaders(),
                      cache: 'no-store'
                    });
                    const text = await response.text();
                    let data = {};
                    try { data = JSON.parse(text); } catch (_) { data = { raw: text }; }
                    statusJson.textContent = JSON.stringify(data, null, 2);
                    const serverAlive = data?.server?.alive === true;
                    const streamClients = data?.server?.streamClients ?? '?';
                    const torch = data?.cameraControls?.torchEnabled;
                    const night = data?.cameraControls?.nightModeEnabled;
                    const batteryPercent = data?.power?.batteryPercent;
                    const charging = data?.power?.charging;
                    const batteryLabel = Number.isFinite(batteryPercent)
                      ? (' battery=' + batteryPercent + '%' + (charging === true ? ' charging' : ''))
                      : '';
                    const audioVolume = data?.audio?.volumePercent;
                    if (Number.isFinite(audioVolume)) {
                      volumeRange.value = String(audioVolume);
                      volumeLabel.textContent = audioVolume + '%';
                    }
                    statusLine.textContent = 'status: http=' + response.status + ' alive=' + serverAlive + ' clients=' + streamClients + ' torch=' + torch + ' night=' + night + batteryLabel;
                    statusLine.className = 'chip mono ' + (response.ok ? 'okText' : 'errText');
                  } catch (error) {
                    statusJson.textContent = 'status error: ' + error;
                    statusLine.textContent = 'status: request failed';
                    statusLine.className = 'chip mono errText';
                  }
                }

                async function setTorch(enabled) {
                  const { response, parsed } = await apiPost('/api/torch', { enabled });
                  if (!response.ok) return;
                  if (parsed && parsed.cameraControls) {
                    statusLine.textContent = 'status: torch=' + parsed.cameraControls.torchEnabled + ' night=' + parsed.cameraControls.nightModeEnabled;
                  }
                  await refreshStatus();
                }

                async function setNightMode(enabled) {
                  const { response, parsed } = await apiPost('/api/nightmode', { enabled });
                  if (!response.ok) return;
                  if (parsed && parsed.cameraControls) {
                    statusLine.textContent = 'status: torch=' + parsed.cameraControls.torchEnabled + ' night=' + parsed.cameraControls.nightModeEnabled;
                  }
                  await refreshStatus();
                }

                async function setAudioLive(mode, enabled) {
                  const { response } = await apiPost('/api/audio/live', { mode, enabled });
                  if (!response.ok) {
                    await refreshStatus();
                    return;
                  }

                  if (mode === 'live') {
                    if (enabled) {
                      const started = await startBrowserLiveListenAudio();
                      if (!started.ok) {
                        appendLog('live listen unavailable: ' + started.reason);
                        stopBrowserLiveListenAudio();
                        await apiPost('/api/audio/live', { mode, enabled: false });
                      }
                    } else {
                      stopBrowserLiveListenAudio();
                    }
                  } else if (mode === 'ptt') {
                    if (enabled) {
                      const started = await startBrowserPushToTalkAudio();
                      if (!started.ok) {
                        appendLog('push-to-talk unavailable: ' + started.reason + (started.detail ? ' | ' + started.detail : ''));
                        stopBrowserPushToTalkAudio();
                        await apiPost('/api/audio/live', { mode, enabled: false });
                      }
                    } else {
                      stopBrowserPushToTalkAudio();
                    }
                  }
                  await refreshStatus();
                }

                async function playSound(clipId, aversive) {
                  await apiPost('/api/audio/play', {
                    clipId,
                    category: aversive ? 'aversive' : 'standard'
                  });
                  await refreshStatus();
                }

                async function setVolumeNow() {
                  await apiPost('/api/volume', { level: Number(volumeRange.value || '0') });
                  await refreshStatus();
                }

                document.getElementById('applyAuthBtn').addEventListener('click', () => {
                  stopBrowserLiveListenAudio();
                  stopBrowserPushToTalkAudio();
                  saveAuthInputs();
                  updateAuthState();
                  updatePairingState();
                  applyPreviewSources();
                  refreshStatus();
                  loadRecordings();
                });

                document.getElementById('clearAuthBtn').addEventListener('click', () => {
                  stopBrowserLiveListenAudio();
                  stopBrowserPushToTalkAudio();
                  tokenInput.value = '';
                  localStorage.removeItem(STORAGE.token);
                  clearPendingPairing(true);
                  updateAuthState();
                  updatePairingState();
                  applyPreviewSources();
                  refreshStatus();
                  recordingsCache = [];
                  recordingEventsCache = [];
                  selectedRecordingName = '';
                  recordingPlayer.removeAttribute('src');
                  recordingPlayer.load();
                  renderRecordings();
                });

                document.getElementById('requestPairingBtn').addEventListener('click', () => {
                  requestBrowserPairing();
                });

                document.getElementById('completePairingBtn').addEventListener('click', () => {
                  completeBrowserPairing();
                });

                document.getElementById('reloadPreviewBtn').addEventListener('click', () => {
                  applyPreviewSources();
                });

                loadRecordingsBtn.addEventListener('click', () => {
                  loadRecordings();
                });

                loadRecentRecordingsBtn.addEventListener('click', () => {
                  setRecentRecordingsRange(12);
                  loadRecordings();
                });

                volumeRange.addEventListener('input', () => {
                  volumeLabel.textContent = volumeRange.value + '%';
                });

                pairingCodeInput.addEventListener('input', () => {
                  pairingCodeInput.value = pairingCodeInput.value.replace(/[^0-9]/g, '').slice(0, 6);
                });

                recordingPlayer.addEventListener('loadedmetadata', () => {
                  if (pendingSeekSeconds !== null) {
                    try {
                      recordingPlayer.currentTime = pendingSeekSeconds;
                    } catch (_) {}
                    pendingSeekSeconds = null;
                  }
                });

                window.addEventListener('beforeunload', () => {
                  stopBrowserLiveListenAudio();
                  stopBrowserPushToTalkAudio();
                });

                tokenInput.value = localStorage.getItem(STORAGE.token) || '';
                deviceIdInput.value = localStorage.getItem(STORAGE.deviceId) || '';
                browserNameInput.value = localStorage.getItem(STORAGE.browserName) || '';
                setRecentRecordingsRange(12);
                ensureBrowserIdentity();
                updateAuthState();
                updatePairingState();
                applyPreviewSources();
                refreshStatus();
                loadRecordings();
                setInterval(refreshStatus, 3000);
                window.setTorch = setTorch;
                window.setNightMode = setNightMode;
                window.setAudioLive = setAudioLive;
                window.playSound = playSound;
                window.setVolumeNow = setVolumeNow;
                window.loadRecordings = loadRecordings;
                window.requestBrowserPairing = requestBrowserPairing;
                window.completeBrowserPairing = completeBrowserPairing;
              </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun decrementActiveClients() {
        while (true) {
            val current = activeStreamClients.get()
            if (current <= 0) return
            if (activeStreamClients.compareAndSet(current, current - 1)) return
        }
    }

    private enum class AudioSocketRole(val wireName: String) {
        LIVE("live"),
        PTT("ptt"),
        UNKNOWN("unknown")
    }

    private companion object {
        const val DEFAULT_PORT = 8080
        const val BOUNDARY = "zcamframe"
        const val AUDIO_SOCKET_PATH = "/ws/audio"
        const val JSON_UTF8 = "application/json; charset=utf-8"
        const val DEFAULT_RECORDINGS_LIST_LIMIT = 120
        const val MAX_RECORDINGS_LIST_LIMIT = 500
        const val AUDIO_SOCKET_QUEUE_CAPACITY = 48

        val JSON_PAIR_REGEX =
            Regex("\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|true|false|null|-?\\d+(?:\\.\\d+)?)")

        val TOO_MANY_REQUESTS_STATUS = object : NanoHTTPD.Response.IStatus {
            override fun getDescription(): String = "429 Too Many Requests"
            override fun getRequestStatus(): Int = 429
        }
    }

    private data class RecordingHttpRange(
        val status: NanoHTTPD.Response.Status,
        val startOffset: Long,
        val contentLength: Long,
        val contentRangeHeader: String?
    )
}

private class MjpegMultipartInputStream(
    private val frameSource: MjpegFrameSource,
    private val boundary: String,
    targetFps: Int,
    private val onOpen: () -> Unit,
    private val onClose: () -> Unit
) : InputStream() {

    private val pollIntervalMs = (1000L / targetFps.coerceAtLeast(1)).coerceAtLeast(20L)
    private val tail = "\r\n".toByteArray(Charsets.US_ASCII)

    private var closed = false
    private var closeNotified = false
    private var segmentIndex = 0
    private var segmentOffset = 0
    private var segments: Array<ByteArray> = emptyArray()
    private var lastFrameRef: ByteArray? = null

    init {
        onOpen()
    }

    override fun read(): Int {
        val singleByte = ByteArray(1)
        val count = read(singleByte, 0, 1)
        return if (count <= 0) -1 else singleByte[0].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed) return -1

        var written = 0
        while (written < length) {
            if (segmentIndex >= segments.size || segmentOffset >= segments[segmentIndex].size) {
                if (!prepareNextChunk()) {
                    return if (written > 0) written else -1
                }
            }

            val segment = segments[segmentIndex]
            val remaining = segment.size - segmentOffset
            val toCopy = minOf(length - written, remaining)
            System.arraycopy(segment, segmentOffset, buffer, offset + written, toCopy)
            segmentOffset += toCopy
            written += toCopy

            if (segmentOffset >= segment.size) {
                segmentIndex += 1
                segmentOffset = 0
            }
        }
        return written
    }

    override fun close() {
        closed = true
        notifyClosedOnce()
    }

    private fun prepareNextChunk(): Boolean {
        while (!closed) {
            val frame = frameSource.latestFrame()
            if (frame.isNotEmpty() && frame !== lastFrameRef) {
                lastFrameRef = frame
                val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
                segments = arrayOf(header, frame, tail)
                segmentIndex = 0
                segmentOffset = 0
                return true
            }

            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                closed = true
            }
        }

        notifyClosedOnce()
        return false
    }

    private fun notifyClosedOnce() {
        if (closeNotified) return
        closeNotified = true
        onClose()
    }
}
