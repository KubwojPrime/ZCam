package com.zcam.client

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OkHttpLocalClient @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : LocalClient {

    private val client = OkHttpClient()

    override suspend fun isServerAlive(target: ClientTarget): Boolean {
        return when (fetchStatus(target)) {
            is ClientCallResult.Success -> true
            is ClientCallResult.Failure -> false
        }
    }

    override suspend fun fetchStatus(target: ClientTarget): ClientCallResult<ClientServerStatus> {
        return executeJsonRequest(
            target = target,
            path = "/api/status",
            method = HttpMethod.GET
        ) { payload ->
            val server = payload.optJSONObject("server")
            val video = payload.optJSONObject("video")
            val cameraControls = payload.optJSONObject("cameraControls")
            val audio = payload.optJSONObject("audio")
            val power = payload.optJSONObject("power")
            ClientServerStatus(
                alive = server?.optBoolean("alive") ?: false,
                overallStatus = payload.optString("status", "unknown"),
                streamClients = server?.optInt("streamClients") ?: 0,
                uptimeMs = server?.optLong("uptimeMs") ?: 0L,
                videoRunning = video?.optBoolean("running") ?: false,
                lastFrameAgeMs = video?.optLong("lastFrameAgeMs") ?: -1L,
                torchEnabled = cameraControls?.optBoolean("torchEnabled") ?: false,
                nightModeEnabled = cameraControls?.optBoolean("nightModeEnabled") ?: false,
                lowLightBoostSupported = cameraControls?.optBoolean("lowLightBoostSupported") ?: false,
                audioTransmitting = audio?.optBoolean("transmitting") ?: false,
                audioLiveListening = audio?.optBoolean("liveListening") ?: false,
                audioPlayingBack = audio?.optBoolean("playingBack") ?: false,
                audioVolumePercent = audio?.optNullableInt("volumePercent"),
                audioMinVolumePercent = audio?.optNullableInt("minVolumePercent"),
                audioMaxVolumePercent = audio?.optNullableInt("maxVolumePercent"),
                batteryPercent = power?.optNullableInt("batteryPercent"),
                charging = power?.optNullableBoolean("charging")
            )
        }
    }

    override fun buildPreviewStreamUrl(target: ClientTarget): String {
        val base = "http://${target.host}:${target.port}/video"
        val query = buildList {
            target.token?.takeIf { it.isNotBlank() }?.let {
                add("token=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
            target.deviceId?.takeIf { it.isNotBlank() }?.let {
                add("deviceId=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
        }.joinToString("&")
        return if (query.isBlank()) base else "$base?$query"
    }

    override suspend fun fetchSnapshot(target: ClientTarget): ClientCallResult<ByteArray> = withContext(dispatchers.io) {
        val request = requestBuilder(target, "/snapshot.jpg")
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes()
                if (!response.isSuccessful || body == null || body.isEmpty()) {
                    val failureBody = body?.toString(Charsets.UTF_8)
                    return@use ClientCallResult.Failure(
                        code = response.code,
                        reason = "snapshot_request_failed",
                        responseBody = failureBody
                    )
                }
                ClientCallResult.Success(body)
            }
        }.getOrElse { error ->
            logger.w("Snapshot fetch failed: ${error.message}")
            ClientCallResult.Failure(
                code = null,
                reason = "snapshot_io_error"
            )
        }
    }

    override suspend fun setPushToTalk(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit> {
        val payload = JSONObject()
            .put("mode", "ptt")
            .put("enabled", enabled)
            .toString()
        return executeAction(target, "/api/audio/live", payload)
    }

    override suspend fun setLiveListen(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit> {
        val payload = JSONObject()
            .put("mode", "live")
            .put("enabled", enabled)
            .toString()
        return executeAction(target, "/api/audio/live", payload)
    }

    override suspend fun setTorch(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit> {
        val payload = JSONObject()
            .put("enabled", enabled)
            .toString()
        return executeAction(target, "/api/torch", payload)
    }

    override suspend fun setNightMode(target: ClientTarget, enabled: Boolean): ClientCallResult<Unit> {
        val payload = JSONObject()
            .put("enabled", enabled)
            .toString()
        return executeAction(target, "/api/nightmode", payload)
    }

    override suspend fun playQuickSound(
        target: ClientTarget,
        clipId: String,
        aversive: Boolean
    ): ClientCallResult<Unit> {
        val payload = JSONObject()
            .put("clipId", clipId)
            .put("category", if (aversive) "aversive" else "standard")
            .toString()
        return executeAction(target, "/api/audio/play", payload)
    }

    override suspend fun setVolume(target: ClientTarget, levelPercent: Int): ClientCallResult<Unit> {
        val payload = JSONObject()
            .put("level", levelPercent)
            .toString()
        return executeAction(target, "/api/volume", payload)
    }

    override suspend fun fetchPairingQr(target: ClientTarget): ClientCallResult<ClientPairingQr> {
        return executeJsonRequest(
            target = target,
            path = "/api/security/pair/qr",
            method = HttpMethod.GET,
            includeAuth = false
        ) { payload ->
            ClientPairingQr(
                sessionId = payload.optString("sessionId"),
                pairingCode = payload.optString("pairingCode"),
                qrPayload = payload.optString("qrPayload"),
                expiresAtEpochMs = payload.optLong("expiresAtEpochMs")
            )
        }
    }

    override suspend fun requestPairing(
        target: ClientTarget,
        deviceId: String,
        displayName: String,
        clientType: String
    ): ClientCallResult<ClientPairingRequest> {
        val payload = JSONObject()
            .put("deviceId", deviceId)
            .put("displayName", displayName)
            .put("clientType", clientType)
            .toString()

        return executeJsonRequest(
            target = target,
            path = "/api/security/pair/request",
            method = HttpMethod.POST,
            includeAuth = false,
            payload = payload
        ) { json ->
            ClientPairingRequest(
                requestId = json.optString("requestId"),
                deviceId = json.optString("deviceId"),
                displayName = json.optString("displayName"),
                expiresAtEpochMs = json.optLong("expiresAtEpochMs")
            )
        }
    }

    override suspend fun completePairingRequest(
        target: ClientTarget,
        requestId: String,
        verificationCode: String
    ): ClientCallResult<ClientPairingResult> {
        val payload = JSONObject()
            .put("requestId", requestId)
            .put("verificationCode", verificationCode)
            .toString()

        return executeJsonRequest(
            target = target,
            path = "/api/security/pair/complete",
            method = HttpMethod.POST,
            includeAuth = false,
            payload = payload
        ) { json ->
            ClientPairingResult(
                tokenId = json.optString("tokenId"),
                tokenValue = json.optString("token"),
                deviceId = json.optString("deviceId")
            )
        }
    }

    override suspend fun pairDevice(
        target: ClientTarget,
        pin: String,
        sessionId: String,
        pairingCode: String,
        deviceId: String,
        displayName: String
    ): ClientCallResult<ClientPairingResult> {
        val payload = JSONObject()
            .put("pin", pin)
            .put("sessionId", sessionId)
            .put("pairingCode", pairingCode)
            .put("deviceId", deviceId)
            .put("displayName", displayName)
            .toString()

        return executeJsonRequest(
            target = target,
            path = "/api/security/pair",
            method = HttpMethod.POST,
            includeAuth = false,
            payload = payload
        ) { json ->
            ClientPairingResult(
                tokenId = json.optString("tokenId"),
                tokenValue = json.optString("token"),
                deviceId = json.optString("deviceId")
            )
        }
    }

    override suspend fun fetchRecordings(
        target: ClientTarget,
        fromEpochMs: Long?,
        toEpochMs: Long?,
        limit: Int
    ): ClientCallResult<List<ClientRecordingSummary>> {
        val base = "http://${target.host}:${target.port}/api/recordings"
        val httpUrl = base.toHttpUrlOrNull()
            ?: return ClientCallResult.Failure(code = null, reason = "invalid_target")
        val urlBuilder = httpUrl.newBuilder()
        fromEpochMs?.let { urlBuilder.addQueryParameter("fromEpochMs", it.toString()) }
        toEpochMs?.let { urlBuilder.addQueryParameter("toEpochMs", it.toString()) }
        urlBuilder.addQueryParameter("limit", limit.coerceIn(1, 500).toString())

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("Connection", "close")

        target.token?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("X-ZCam-Token", it) }
        target.deviceId?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("X-ZCam-Device-Id", it) }

        return withContext(dispatchers.io) {
            runCatching {
                client.newCall(requestBuilder.get().build()).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use ClientCallResult.Failure(
                            code = response.code,
                            reason = extractErrorReason(bodyString) ?: "http_${response.code}",
                            responseBody = bodyString
                        )
                    }

                    val payload = if (bodyString.isBlank()) JSONObject() else JSONObject(bodyString)
                    val items = payload.optJSONArray("recordings") ?: JSONArray()
                    val recordings = buildList {
                        for (index in 0 until items.length()) {
                            val item = items.optJSONObject(index) ?: continue
                            add(
                                ClientRecordingSummary(
                                    fileName = item.optString("fileName"),
                                    startedAtEpochMs = item.optLong("startedAtEpochMs"),
                                    endedAtEpochMs = item.optLong("endedAtEpochMs"),
                                    durationMs = item.optLong("durationMs"),
                                    sizeBytes = item.optLong("sizeBytes"),
                                    container = item.optString("container"),
                                    codec = item.optString("codec")
                                )
                            )
                        }
                    }
                    ClientCallResult.Success(recordings)
                }
            }.getOrElse { error ->
                logger.w("Fetch recordings failed: ${error.message}")
                ClientCallResult.Failure(
                    code = null,
                    reason = "io_error",
                    responseBody = error.message
                )
            }
        }
    }

    override suspend fun fetchRecordingEvents(
        target: ClientTarget,
        fromEpochMs: Long?,
        toEpochMs: Long?,
        limit: Int
    ): ClientCallResult<List<ClientRecordingEvent>> {
        val base = "http://${target.host}:${target.port}/api/recordings/events"
        val httpUrl = base.toHttpUrlOrNull()
            ?: return ClientCallResult.Failure(code = null, reason = "invalid_target")
        val urlBuilder = httpUrl.newBuilder()
        fromEpochMs?.let { urlBuilder.addQueryParameter("fromEpochMs", it.toString()) }
        toEpochMs?.let { urlBuilder.addQueryParameter("toEpochMs", it.toString()) }
        urlBuilder.addQueryParameter("limit", limit.coerceIn(1, 1000).toString())

        val requestBuilder = Request.Builder()
            .url(urlBuilder.build())
            .header("Accept", "application/json")
            .header("Connection", "close")

        target.token?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("X-ZCam-Token", it) }
        target.deviceId?.takeIf { it.isNotBlank() }?.let { requestBuilder.header("X-ZCam-Device-Id", it) }

        return withContext(dispatchers.io) {
            runCatching {
                client.newCall(requestBuilder.get().build()).execute().use { response ->
                    val bodyString = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@use ClientCallResult.Failure(
                            code = response.code,
                            reason = extractErrorReason(bodyString) ?: "http_${response.code}",
                            responseBody = bodyString
                        )
                    }

                    val payload = if (bodyString.isBlank()) JSONObject() else JSONObject(bodyString)
                    val items = payload.optJSONArray("events") ?: JSONArray()
                    val events = buildList {
                        for (index in 0 until items.length()) {
                            val item = items.optJSONObject(index) ?: continue
                            add(
                                ClientRecordingEvent(
                                    epochMs = item.optLong("epochMs"),
                                    confidencePercent = item.optInt("confidencePercent"),
                                    source = item.optString("source"),
                                    recordingFileName = item.optString("recordingFileName").ifBlank { null }
                                )
                            )
                        }
                    }
                    ClientCallResult.Success(events)
                }
            }.getOrElse { error ->
                logger.w("Fetch recording events failed: ${error.message}")
                ClientCallResult.Failure(
                    code = null,
                    reason = "io_error",
                    responseBody = error.message
                )
            }
        }
    }

    override fun buildRecordingPlaybackUrl(target: ClientTarget, fileName: String): String {
        val encodedName = URLEncoder.encode(fileName, StandardCharsets.UTF_8.name())
        val base = "http://${target.host}:${target.port}/api/recordings/$encodedName"
        val query = buildList {
            target.token?.takeIf { it.isNotBlank() }?.let {
                add("token=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
            target.deviceId?.takeIf { it.isNotBlank() }?.let {
                add("deviceId=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
        }.joinToString("&")
        return if (query.isBlank()) base else "$base?$query"
    }

    private suspend fun executeAction(
        target: ClientTarget,
        path: String,
        payload: String
    ): ClientCallResult<Unit> {
        return executeJsonRequest(
            target = target,
            path = path,
            method = HttpMethod.POST,
            payload = payload
        ) { _ -> Unit }
    }

    private suspend fun <T> executeJsonRequest(
        target: ClientTarget,
        path: String,
        method: HttpMethod,
        includeAuth: Boolean = true,
        payload: String? = null,
        mapper: (JSONObject) -> T
    ): ClientCallResult<T> = withContext(dispatchers.io) {
        val builder = requestBuilder(target, path, includeAuth = includeAuth)
        when (method) {
            HttpMethod.GET -> builder.get()
            HttpMethod.POST -> builder.post((payload ?: "{}").toRequestBody(JSON_MEDIA))
        }
        val request = builder.build()

        runCatching {
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use ClientCallResult.Failure(
                        code = response.code,
                        reason = extractErrorReason(bodyString) ?: "http_${response.code}",
                        responseBody = bodyString
                    )
                }

                val json = if (bodyString.isBlank()) JSONObject() else JSONObject(bodyString)
                ClientCallResult.Success(mapper(json))
            }
        }.getOrElse { error ->
            logger.w("HTTP call failed path=$path error=${error.message}")
            val reason = when (error) {
                is java.net.UnknownHostException -> "host_not_found"
                is java.net.ConnectException -> "connect_error"
                is java.net.SocketTimeoutException -> "timeout"
                else -> "io_error"
            }
            ClientCallResult.Failure(
                code = null,
                reason = reason,
                responseBody = error.message
            )
        }
    }

    private fun requestBuilder(
        target: ClientTarget,
        path: String,
        includeAuth: Boolean = true
    ): Request.Builder {
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val builder = Request.Builder()
            .url("http://${target.host}:${target.port}$normalizedPath")
            .header("Accept", "application/json")
            .header("Connection", "close")

        if (includeAuth) {
            target.token?.takeIf { it.isNotBlank() }?.let { token ->
                builder.header("X-ZCam-Token", token)
            }
            target.deviceId?.takeIf { it.isNotBlank() }?.let { deviceId ->
                builder.header("X-ZCam-Device-Id", deviceId)
            }
        }
        return builder
    }

    private fun extractErrorReason(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            JSONObject(body).optString("reason").ifBlank { null }
                ?: JSONObject(body).optString("message").ifBlank { null }
        }.getOrNull()
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        return if (has(name) && !isNull(name)) optInt(name) else null
    }

    private fun JSONObject.optNullableBoolean(name: String): Boolean? {
        return if (has(name) && !isNull(name)) optBoolean(name) else null
    }

    private enum class HttpMethod {
        GET,
        POST
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
