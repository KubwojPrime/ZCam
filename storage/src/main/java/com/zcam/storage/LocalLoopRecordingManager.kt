package com.zcam.storage

import android.content.Context
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.LoopRecordingConfig
import com.zcam.core.logging.ZCamLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLoopRecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : LoopRecordingManager {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private var loopJob: Job? = null
    private var activeConfig: LoopRecordingConfig = LoopRecordingConfig()

    override suspend fun start(config: LoopRecordingConfig) = withContext(dispatchers.io) {
        activeConfig = config
        if (loopJob?.isActive == true) return@withContext

        val recordingDir = recordingDir()
        recordingDir.mkdirs()

        loopJob = scope.launch {
            logger.i("Loop recording started with ${activeConfig.segmentMinutes} minute segments")
            while (isActive) {
                writeSegmentMarker(recordingDir)
                enforceRetention(recordingDir, activeConfig)
                delay(activeConfig.segmentMinutes * 60_000L)
            }
        }
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        loopJob?.cancel()
        loopJob = null
        logger.i("Loop recording stopped")
    }

    override suspend fun forceRetentionSweep() = withContext(dispatchers.io) {
        enforceRetention(recordingDir(), activeConfig)
    }

    override suspend fun isHealthy(): Boolean = withContext(dispatchers.io) {
        loopJob?.isActive == true
    }

    private fun recordingDir(): File = File(context.filesDir, "zcam_recordings")

    private fun writeSegmentMarker(dir: File) {
        val file = File(dir, "segment_${System.currentTimeMillis()}.txt")
        file.writeText("segment placeholder")
    }

    private fun enforceRetention(dir: File, config: LoopRecordingConfig) {
        val maxStorageBytes = config.maxStorageGb.toLong() * 1024L * 1024L * 1024L
        val minFreeBytes = config.minFreeStorageGb.toLong() * 1024L * 1024L * 1024L

        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
        var totalSize = files.sumOf { it.length() }

        for (file in files) {
            val free = dir.usableSpace
            if (totalSize <= maxStorageBytes && free >= minFreeBytes) {
                break
            }
            totalSize -= file.length()
            file.delete()
        }
    }
}
