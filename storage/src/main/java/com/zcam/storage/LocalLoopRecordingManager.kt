package com.zcam.storage

import android.content.Context
import android.os.Environment
import com.zcam.camera.VideoRecordingPipeline
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.config.LoopRecordingConfig
import com.zcam.core.domain.recording.RecordingEventStore
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
    private val recordingEventStore: RecordingEventStore,
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
                "Loop recording started with ${config.segmentMinutes} min segments, max=${config.maxStorageGb}GB minFree=${config.minFreeStorageGb}GB dir=${directory.absolutePath}"
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

    override suspend fun queryRecordings(
        fromEpochMs: Long?,
        toEpochMs: Long?,
        limit: Int
    ): List<RecordingClipSummary> = withContext(dispatchers.io) {
        val normalizedLimit = limit.coerceIn(1, MAX_RECORDINGS_QUERY_LIMIT)
        val normalizedFrom = fromEpochMs?.coerceAtLeast(0L)
        val normalizedTo = toEpochMs?.coerceAtLeast(0L)

        stateMutex.withLock {
            val directory = recordingDir()
            val repository = indexRepository ?: RecordingIndexRepository(indexFile(directory)).also {
                indexRepository = it
            }
            val catalog = repository.loadOrRebuild(directory, logger)
            currentCatalog = catalog
            return@withLock catalog.segments
                .asSequence()
                .filter { segment ->
                    val overlapsFrom = normalizedFrom?.let { segment.endedAtEpochMs >= it } ?: true
                    val overlapsTo = normalizedTo?.let { segment.startedAtEpochMs <= it } ?: true
                    overlapsFrom && overlapsTo
                }
                .sortedByDescending { it.startedAtEpochMs }
                .take(normalizedLimit)
                .map { segment ->
                    RecordingClipSummary(
                        fileName = segment.fileName,
                        startedAtEpochMs = segment.startedAtEpochMs,
                        endedAtEpochMs = segment.endedAtEpochMs,
                        sizeBytes = segment.sizeBytes,
                        container = segment.container,
                        codec = segment.codec
                    )
                }
                .toList()
        }
    }

    override suspend fun queryRecordingEvents(
        fromEpochMs: Long?,
        toEpochMs: Long?,
        limit: Int
    ): List<RecordingEventSummary> = withContext(dispatchers.io) {
        val events = recordingEventStore.query(
            fromEpochMs = fromEpochMs,
            toEpochMs = toEpochMs,
            limit = limit.coerceIn(1, MAX_RECORDING_EVENTS_QUERY_LIMIT)
        )
        val catalog = stateMutex.withLock {
            val directory = recordingDir()
            val repository = indexRepository ?: RecordingIndexRepository(indexFile(directory)).also {
                indexRepository = it
            }
            repository.loadOrRebuild(directory, logger).also {
                currentCatalog = it
            }
        }
        return@withContext events.map { event ->
            val recordingFileName = catalog.segments.firstOrNull { segment ->
                event.epochMs in segment.startedAtEpochMs..segment.endedAtEpochMs
            }?.fileName
            RecordingEventSummary(
                epochMs = event.epochMs,
                confidencePercent = event.confidencePercent,
                source = event.source,
                recordingFileName = recordingFileName
            )
        }
    }

    override suspend fun resolveRecordingFile(fileName: String): File? = withContext(dispatchers.io) {
        if (!RECORDING_FILE_NAME_REGEX.matches(fileName)) return@withContext null
        val directory = recordingDir()
        val directoryCanonical = runCatching { directory.canonicalFile }.getOrElse { return@withContext null }
        val candidate = runCatching { File(directoryCanonical, fileName).canonicalFile }.getOrElse { return@withContext null }
        val rootPath = directoryCanonical.path.trimEnd(File.separatorChar) + File.separator
        if (!candidate.path.startsWith(rootPath)) return@withContext null
        if (!candidate.exists() || !candidate.isFile) return@withContext null
        if (!candidate.extension.equals("mp4", ignoreCase = true)) return@withContext null
        return@withContext candidate
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
            } catch (error: StorageHeadroomException) {
                if (!coroutineContext.isActive) return
                val recovered = attemptStorageHeadroomRecovery(
                    error = error,
                    recordingDir = recordingDir,
                    repository = repository,
                    currentCatalog = catalog,
                    config = config
                )
                if (recovered != null) {
                    catalog = recovered
                    healthy.set(true)
                    continue
                }
                healthy.set(false)
                logger.e(
                    LogEventId.RECORDING_SEGMENT_FAILED,
                    error,
                    "Storage headroom recovery exhausted; runtime restart required"
                )
                logger.w(
                    LogEventId.RECORDING_RECOVERY_REQUIRED,
                    "Storage pipeline marked unhealthy after out-of-space retries"
                )
                runCatching { videoRecordingPipeline.abortVideoSegment() }
                return
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

    private suspend fun attemptStorageHeadroomRecovery(
        error: StorageHeadroomException,
        recordingDir: File,
        repository: RecordingIndexRepository,
        currentCatalog: RecordingCatalog,
        config: LoopRecordingConfig
    ): RecordingCatalog? {
        var catalog = currentCatalog
        for (attempt in 1..MAX_OUT_OF_SPACE_RECOVERY_ATTEMPTS) {
            logger.w(
                LogEventId.RECORDING_OUT_OF_SPACE,
                "Out-of-space recovery attempt $attempt/$MAX_OUT_OF_SPACE_RECOVERY_ATTEMPTS: ${error.message}"
            )

            catalog = enforceStoragePolicy(recordingDir, repository, catalog, config)
            val hasHeadroom = hasStorageHeadroom(recordingDir, catalog, config)
            if (hasHeadroom) {
                logger.i(
                    LogEventId.RECORDING_OUT_OF_SPACE_RECOVERED,
                    "Storage headroom recovered after attempt $attempt"
                )
                return catalog
            }

            val delayMs = BASE_OUT_OF_SPACE_BACKOFF_MS * attempt
            delay(delayMs)
        }
        return null
    }

    private suspend fun enforceStoragePolicy(
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
        retentionOutcome.catalog.segments.minOfOrNull(RecordingSegmentEntry::startedAtEpochMs)?.let { oldestRetained ->
            recordingEventStore.pruneBefore(oldestRetained)
        }
        repository.save(retentionOutcome.catalog)
        return retentionOutcome.catalog
    }

    private fun ensureStorageHeadroom(
        recordingDir: File,
        catalog: RecordingCatalog,
        config: LoopRecordingConfig
    ) {
        if (hasStorageHeadroom(recordingDir, catalog, config)) return
        val freeBytes = recordingDir.usableSpace
        val totalBytes = catalog.totalBytes
        val maxStorageBytes = bytesFromGb(config.maxStorageGb)
        val minFreeBytes = bytesFromGb(config.minFreeStorageGb)
        throw StorageHeadroomException(
            "storage headroom insufficient total=$totalBytes max=$maxStorageBytes free=$freeBytes minFree=$minFreeBytes"
        )
    }

    private fun hasStorageHeadroom(
        recordingDir: File,
        catalog: RecordingCatalog,
        config: LoopRecordingConfig
    ): Boolean {
        val freeBytes = recordingDir.usableSpace
        val totalBytes = catalog.totalBytes
        val maxStorageBytes = bytesFromGb(config.maxStorageGb)
        val minFreeBytes = bytesFromGb(config.minFreeStorageGb)
        val withinQuota = totalBytes <= maxStorageBytes
        val enoughFree = freeBytes >= minFreeBytes
        return withinQuota && enoughFree
    }

    private fun recordingDir(): File {
        val externalBase = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        return if (externalBase != null) {
            File(externalBase, "ZCam/recordings")
        } else {
            File(context.filesDir, "zcam_recordings")
        }
    }
    private fun indexFile(recordingDir: File): File = File(recordingDir, "recording_index_v1.tsv")

    private class StorageHeadroomException(message: String) : IllegalStateException(message)

    private companion object {
        const val MAX_OUT_OF_SPACE_RECOVERY_ATTEMPTS = 6
        const val BASE_OUT_OF_SPACE_BACKOFF_MS = 1_500L
        const val MAX_RECORDINGS_QUERY_LIMIT = 500
        const val MAX_RECORDING_EVENTS_QUERY_LIMIT = 1_000
        val RECORDING_FILE_NAME_REGEX = Regex("^[A-Za-z0-9._-]{1,128}\\.mp4$")
    }
}
