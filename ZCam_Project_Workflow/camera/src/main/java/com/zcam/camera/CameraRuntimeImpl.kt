package com.zcam.camera

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CameraRuntimeImpl @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : CameraRuntime, MjpegFrameSource {

    private val latest = AtomicReference(PLACEHOLDER_JPEG)
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var producerJob: Job? = null

    override suspend fun start() {
        if (producerJob?.isActive == true) return
        logger.i("Camera runtime start requested")
        producerJob = scope.launch {
            while (isActive) {
                latest.set(buildPlaceholderFrame())
                delay(66L)
            }
        }
    }

    override suspend fun stop() {
        logger.i("Camera runtime stop requested")
        producerJob?.cancel()
        producerJob = null
    }

    override fun latestFrame(): ByteArray = latest.get()

    private fun buildPlaceholderFrame(): ByteArray {
        return PLACEHOLDER_JPEG
    }

    private companion object {
        // 1x1 JPEG used as a stable stream placeholder until CameraX encoder is wired in.
        val PLACEHOLDER_JPEG: ByteArray = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQAAAQABAAD/2wCEAAkGBxAQEBIQEA8PEA8PDw8PDw8PDw8PDw8PFREWFhURFRUYHSggGBolGxUVITEhJSkrLi4uFx8zODMsNygtLisBCgoKDg0OGhAQGi0mHyYtLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLS0tLf/AABEIAAEAAQMBEQACEQEDEQH/xAAXAAEAAwAAAAAAAAAAAAAAAAAAAQID/8QAFhEBAQEAAAAAAAAAAAAAAAAAAAER/9oADAMBAAIQAxAAAAG0A//EABkQAQADAQEAAAAAAAAAAAAAAAIAAREhMf/aAAgBAQABBQJQ0ZQ4x4f/xAAVEQEBAAAAAAAAAAAAAAAAAAAAEf/aAAgBAwEBPwFH/8QAFhEBAQEAAAAAAAAAAAAAAAAAABEh/9oACAECAQE/AYf/xAAbEAACAgMBAAAAAAAAAAAAAAABEQAhMUFhcf/aAAgBAQAGPwKQ4Yq0cYv/xAAZEAEAAgMAAAAAAAAAAAAAAAABABEhMUH/2gAIAQEAAT8h0qVI0rWg/wD/2Q=="
        )
    }
}
