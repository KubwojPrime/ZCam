package com.zcam.server

import com.zcam.camera.FramePipelineStatusSource
import com.zcam.camera.MjpegFrameSource
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import com.zcam.security.LanAccessPolicy
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZCamHttpServer @Inject constructor(
    private val frameSource: MjpegFrameSource,
    private val frameStatusSource: FramePipelineStatusSource,
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

        activePort = port
        val httpServer = object : NanoHTTPD(port) {
            override fun serve(session: IHTTPSession): Response {
                val remoteIp = session.remoteIpAddress
                if (!lanAccessPolicy.isLanClient(remoteIp)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "LAN only")
                }

                return when (session.uri.orEmpty()) {
                    "/" -> buildPanelResponse()
                    "/health" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
                    "/api/status" -> buildStatusResponse()
                    "/video", "/mjpeg" -> buildVideoResponse()
                    "/snapshot.jpg" -> buildSnapshotResponse()
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
                }
            }
        }

        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        startedAtEpochMs.set(System.currentTimeMillis())
        server = httpServer
        logger.i("HTTP server started on port $activePort")
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        server?.stop()
        server = null
        activeStreamClients.set(0)
        startedAtEpochMs.set(0L)
        logger.i("HTTP server stopped")
    }

    override suspend fun isHealthy(): Boolean = withContext(dispatchers.io) {
        server?.isAlive == true
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
            "application/json; charset=utf-8",
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

        return buildString(capacity = 512) {
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
            append('}')
            append('}')
        }
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
