package com.zcam.storage

import android.content.Context
import com.zcam.camera.VideoRecordingPipeline
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.LoopRecordingConfig
import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.e
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class LocalLoopRecordingManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val videoRecordingPipeline: VideoRecordingPipeline,
    private val logger: ZCamLogger
) : LoopRecordingManager {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val stateMutex = Mutex()
    private val healthy = AtomicBoolean(false)

    private val retentionPolicy = RecordingRetentionPolicy()

    private var workerJob: Job? = null
    private var activeConfig: LoopRecordingConfig = LoopRecordingConfig()
    private var indexRepository: RecordingIndexRepository? = null
    private var currentCatalog = RecordingCatalog(emptyList())

    override suspend fun start(config: LoopRecordingConfig) = withContext(dispatchers.io) {
        stateMutex.withLock {
            activeConfig = config
            if (workerJob?.isActive == true) return@withLock

            val directory = recordingDir()
            directory.mkdirs()
            val repository = RecordingIndexRepository(indexFile(directory))
            indexRepository = repository
            currentCatalog = repository.loadOrRebuild(directory, logger)
            healthy.set(true)

            workerJob = scope.launch {
                runRecordingLoop(
                    config = config,
                    recordingDir = directory,
                    repository = repository
                )
            }

            logger.i(
                LogEventId.RECORDING_STARTED,
                "Loop recording started with ${config.segmentMinutes} min segments, max=${config.maxStorageGb}GB minFree=${config.minFreeStorageGb}GB"
            )
        }
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        val jobToStop = stateMutex.withLock {
            val runningJob = workerJob
            workerJob = null
            healthy.set(false)
            runningJob
        }
        jobToStop?.cancel()
        videoRecordingPipeline.abortVideoSegment()
        logger.i(LogEventId.RECORDING_STOPPED, "Loop recording stopped")
    }

    override suspend fun forceRetentionSweep() = withContext(dispatchers.io) {
        stateMutex.withLock {
            val directory = recordingDir()
            val repository = indexRepository ?: RecordingIndexRepository(indexFile(directory)).also {
                indexRepository = it
            }
            val loaded = repository.loadOrRebuild(directory, logger)
            val outcome = retentionPolicy.enforce(
                recordingDir = directory,
                initialCatalog = loaded,
                maxStorageBytes = bytesFromGb(activeConfig.maxStorageGb),
                minFreeBytes = bytesFromGb(activeConfig.minFreeStorageGb),
                logger = logger
            )
            currentCatalog = outcome.catalog
            repository.save(currentCatalog)
        }
    }

    override suspend fun isHealthy(): Boolean = withContext(dispatchers.io) {
        healthy.get() && workerJob?.isActive == true
    }

    private suspend fun runRecordingLoop(
        config: LoopRecordingConfig,
        recordingDir: File,
        repository: RecordingIndexRepository
    ) {
        val segmentDurationMs = config.segmentMinutes.coerceAtLeast(1) * 60_000L
        var catalog = currentCatalog

        while (coroutineContext.isActive) {
            try {
                catalog = enforceStoragePolicy(recordingDir, repository, catalog, config)
                ensureStorageHeadroom(recordingDir, catalog, config)

                val startedAt = System.currentTimeMillis()
                val outputFile = File(recordingDir, "segment_${startedAt}.mp4")
                logger.i(
                    LogEventId.RECORDING_SEGMENT_STARTED,
                    "Starting recording segment ${outputFile.name}"
                )

                videoRecordingPipeline.startVideoSegment(outputFile)
                delay(segmentDurationMs)
                val result = videoRecordingPipeline.stopVideoSegment()

                if (!result.success) {
                    throw IllegalStateException(
                        "segment finalize failed code=${result.errorCode} message=${result.errorMessage}"
                    )
                }

                val segment = RecordingSegmentEntry(
                    fileName = result.file.name,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = result.finalizedAtEpochMs,
                    sizeBytes = result.bytesWritten,
                    container = "MP4",
                    codec = "H264"
                )

                catalog = RecordingCatalog((catalog.segments + segment).sortedBy { it.startedAtEpochMs })
                repository.save(catalog)
                logger.i(
                    LogEventId.RECORDING_SEGMENT_FINALIZED,
                    "Segment finalized ${segment.fileName} (${segment.sizeBytes} bytes)"
                )

                catalog = enforceStoragePolicy(recordingDir, repository, catalog, config)
                healthy.set(true)
            } catch (error: Throwable) {
                if (!coroutineContext.isActive) return
                healthy.set(false)
                logger.e(
                    LogEventId.RECORDING_SEGMENT_FAILED,
                    error,
                    "Loop recording pipeline failed; runtime recovery required (${error.message})"
                )
                logger.w(
                    LogEventId.RECORDING_RECOVERY_REQUIRED,
                    "Storage pipeline marked unhealthy and waiting for runtime restart"
                )
                runCatching { videoRecordingPipeline.abortVideoSegment() }
                return
            }
        }
    }

    private fun enforceStoragePolicy(
        recordingDir: File,
        repository: RecordingIndexRepository,
        catalog: RecordingCatalog,
        config: LoopRecordingConfig
    ): RecordingCatalog {
        val retentionOutcome = retentionPolicy.enforce(
            recordingDir = recordingDir,
            initialCatalog = catalog,
            maxStorageBytes = bytesFromGb(config.maxStorageGb),
            minFreeBytes = bytesFromGb(config.minFreeStorageGb),
            logger = logger
        )
        if (retentionOutcome.deletedSegments > 0) {
            logger.i(
                LogEventId.RECORDING_CLEANUP_REMOVED,
                "Cleanup removed ${retentionOutcome.deletedSegments} segments (${retentionOutcome.deletedBytes} bytes)"
            )
        }
        repository.save(retentionOutcome.catalog)
        return retentionOutcome.catalog
    }

    private fun ensureStorageHeadroom(
        recordingDir: File,
        catalog: RecordingCatalog,
        config: LoopRecordingConfig
    ) {
        val freeBytes = recordingDir.usableSpace
        val totalBytes = catalog.totalBytes
        val maxStorageBytes = bytesFromGb(config.maxStorageGb)
        val minFreeBytes = bytesFromGb(config.minFreeStorageGb)
        val withinQuota = totalBytes <= maxStorageBytes
        val enoughFree = freeBytes >= minFreeBytes
        if (!withinQuota || !enoughFree) {
            throw IllegalStateException(
                "storage headroom insufficient total=$totalBytes max=$maxStorageBytes free=$freeBytes minFree=$minFreeBytes"
            )
        }
    }

    private fun recordingDir(): File = File(context.filesDir, "zcam_recordings")
    private fun indexFile(recordingDir: File): File = File(recordingDir, "recording_index_v1.tsv")
}
