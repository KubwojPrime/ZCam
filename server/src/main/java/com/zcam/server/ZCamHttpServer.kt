package com.zcam.server

import com.zcam.audio.AudioCommandErrorCode
import com.zcam.audio.AudioCommandResult
import com.zcam.audio.AudioLiveMode
import com.zcam.audio.AudioPlaybackCategory
import com.zcam.audio.AudioPlaybackRequest
import com.zcam.audio.PushToTalkManager
import com.zcam.camera.FramePipelineStatusSource
import com.zcam.camera.MjpegFrameSource
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import com.zcam.security.LanAccessPolicy
import com.zcam.security.PairingResult
import com.zcam.security.SecurityManager
import com.zcam.security.TokenRevocationResult
import com.zcam.security.TokenRotationResult
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZCamHttpServer @Inject constructor(
    private val frameSource: MjpegFrameSource,
    private val frameStatusSource: FramePipelineStatusSource,
    private val pushToTalkManager: PushToTalkManager,
    private val securityManager: SecurityManager,
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

    override suspend fun start(port: Int) = withContext(dispatchers.io) {
        if (server != null) return@withContext

        securityManager.sanityCheckAfterRestart()
        activePort = port
        val httpServer = object : NanoHTTPD(port) {
            override fun serve(session: IHTTPSession): Response {
                val remoteIp = session.remoteIpAddress
                if (!lanAccessPolicy.isLanClient(remoteIp)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "LAN only")
                }

                val uri = session.uri.orEmpty()
                if (!isPublicEndpoint(uri, session.method)) {
                    val auth = runBlocking {
                        securityManager.authorizeRequest(
                            tokenCandidate = extractToken(session),
                            deviceId = extractDeviceId(session)
                        )
                    }
                    if (!auth.allowed) {
                        return newFixedLengthResponse(
                            toStatus(auth.statusCode),
                            JSON_UTF8,
                            "{\"status\":\"error\",\"reason\":${jsonString(auth.reason)}}"
                        )
                    }
                }

                return runCatching {
                    routeRequest(session)
                }.getOrElse { error ->
                    logger.e(LogEventId.COMPONENT_FAILED, error, "HTTP request handling failed for ${session.uri}")
                    newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "internal error")
                }
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

    private fun routeRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        return when (session.uri.orEmpty()) {
            "/" -> buildPanelResponse()
            "/health" -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_PLAINTEXT, "ok")
            "/api/status" -> buildStatusResponse()
            "/video", "/mjpeg" -> buildVideoResponse()
            "/snapshot.jpg" -> buildSnapshotResponse()
            "/api/audio/live" -> handleAudioLiveEndpoint(session)
            "/api/audio/play" -> handleAudioPlayEndpoint(session)
            "/api/volume" -> handleVolumeEndpoint(session)
            "/api/security/pair/qr" -> handlePairingQrEndpoint(session)
            "/api/security/pair" -> handlePairDeviceEndpoint(session)
            "/api/security/token/rotate" -> handleTokenRotateEndpoint(session)
            "/api/security/token/revoke" -> handleTokenRevokeEndpoint(session)
            else -> NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "not found")
        }
    }

    private fun handlePairingQrEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (session.method != NanoHTTPD.Method.GET) {
            return methodNotAllowed("GET")
        }

        val challenge = runBlocking { securityManager.createPairingChallenge() }
        val host = session.headers["host"].orEmpty()
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
            is PairingResult.Success -> {
                val body = buildString {
                    append('{')
                    append("\"status\":\"ok\",")
                    append("\"tokenId\":").append(jsonString(result.tokenId)).append(',')
                    append("\"token\":").append(jsonString(result.tokenValue)).append(',')
                    append("\"deviceId\":").append(jsonString(result.deviceId))
                    append('}')
                }
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, JSON_UTF8, body)
            }
            is PairingResult.Failure -> {
                NanoHTTPD.newFixedLengthResponse(
                    toStatus(result.statusCode),
                    JSON_UTF8,
                    "{\"status\":\"error\",\"reason\":${jsonString(result.reason)}}"
                )
            }
        }
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

    private fun extractToken(session: NanoHTTPD.IHTTPSession): String? {
        val headerLookup = session.headers.entries.associate { it.key.lowercase() to it.value }
        val bearer = headerLookup["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)
            ?.trim()
        val token = bearer ?: headerLookup["x-zcam-token"] ?: headerLookup["x-api-token"]
        return token?.trim()?.ifBlank { null }
    }

    private fun extractDeviceId(session: NanoHTTPD.IHTTPSession): String? {
        val headerLookup = session.headers.entries.associate { it.key.lowercase() to it.value }
        return (headerLookup["x-zcam-device-id"] ?: headerLookup["x-device-id"])
            ?.trim()
            ?.ifBlank { null }
    }

    private fun isPublicEndpoint(uri: String, method: NanoHTTPD.Method): Boolean {
        if (uri == "/health") return true
        if (uri == "/api/security/pair/qr" && method == NanoHTTPD.Method.GET) return true
        if (uri == "/api/security/pair" && method == NanoHTTPD.Method.POST) return true
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
        val lastFrameAgeMs = if (frame.lastFrameEpochMs > 0L) {
            (now - frame.lastFrameEpochMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        val audio = pushToTalkManager.snapshotState()

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
            append("\"audio\":").append(audioStateJson(audio))
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
              <title>ZCam</title>
              <style>
                body { font-family: sans-serif; margin: 16px; background: #111; color: #eee; }
                .card { max-width: 980px; margin: 0 auto; padding: 16px; background: #1b1b1b; border-radius: 8px; }
                img { width: 100%; height: auto; border-radius: 6px; background: #000; }
                a { color: #82cfff; }
                pre { white-space: pre-wrap; word-break: break-word; background: #101010; padding: 12px; border-radius: 6px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>ZCam</h1>
                <p><a href="/snapshot.jpg" target="_blank">Open snapshot</a> | <a href="/api/status" target="_blank">Open status JSON</a></p>
                <img src="/video" alt="ZCam stream">
                <h2>Status</h2>
                <pre id="status">loading...</pre>
              </div>
              <script>
                async function refreshStatus() {
                  try {
                    const response = await fetch('/api/status', { cache: 'no-store' });
                    const data = await response.json();
                    document.getElementById('status').textContent = JSON.stringify(data, null, 2);
                  } catch (error) {
                    document.getElementById('status').textContent = 'status error: ' + error;
                  }
                }
                refreshStatus();
                setInterval(refreshStatus, 2000);
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

    private companion object {
        const val DEFAULT_PORT = 8080
        const val BOUNDARY = "zcamframe"
        const val JSON_UTF8 = "application/json; charset=utf-8"

        val JSON_PAIR_REGEX =
            Regex("\"([^\"]+)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\"|true|false|null|-?\\d+(?:\\.\\d+)?)")

        val TOO_MANY_REQUESTS_STATUS = object : NanoHTTPD.Response.IStatus {
            override fun getDescription(): String = "429 Too Many Requests"
            override fun getRequestStatus(): Int = 429
        }
    }
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
