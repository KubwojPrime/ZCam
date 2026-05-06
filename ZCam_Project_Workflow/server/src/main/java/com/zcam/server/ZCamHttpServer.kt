package com.zcam.server

import com.zcam.camera.MjpegFrameSource
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import com.zcam.security.LanAccessPolicy
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZCamHttpServer @Inject constructor(
    private val frameSource: MjpegFrameSource,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger,
    private val lanAccessPolicy: LanAccessPolicy
) : LocalHttpServer {

    @Volatile
    private var server: NanoHTTPD? = null

    override suspend fun start() = withContext(dispatchers.io) {
        if (server != null) return@withContext

        val httpServer = object : NanoHTTPD(DEFAULT_PORT) {
            override fun serve(session: IHTTPSession): Response {
                val remoteIp = session.remoteIpAddress
                if (!lanAccessPolicy.isLanClient(remoteIp)) {
                    return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "LAN only")
                }

                return when (session.uri) {
                    "/health" -> newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "ok")
                    "/mjpeg" -> {
                        val stream = MjpegMultipartInputStream(frameSource)
                        newChunkedResponse(
                            Response.Status.OK,
                            "multipart/x-mixed-replace; boundary=$BOUNDARY",
                            stream
                        )
                    }
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
                }
            }
        }

        httpServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = httpServer
        logger.i("HTTP server started on port $DEFAULT_PORT")
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        server?.stop()
        server = null
        logger.i("HTTP server stopped")
    }

    private companion object {
        const val DEFAULT_PORT = 8080
        const val BOUNDARY = "zcamframe"
    }
}

private class MjpegMultipartInputStream(
    private val frameSource: MjpegFrameSource
) : InputStream() {

    private var currentChunk = ByteArrayInputStream(nextChunk())

    override fun read(): Int {
        var value = currentChunk.read()
        while (value == -1) {
            try {
                Thread.sleep(66L)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return -1
            }
            currentChunk = ByteArrayInputStream(nextChunk())
            value = currentChunk.read()
        }
        return value
    }

    private fun nextChunk(): ByteArray {
        val frame = frameSource.latestFrame()
        val header = "--zcamframe\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        val tail = "\r\n".toByteArray(Charsets.US_ASCII)
        return header + frame + tail
    }
}
