package com.zcam.storage

import android.content.Context
import android.os.Environment
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.recording.RecordingEvent
import com.zcam.core.domain.recording.RecordingEventStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRecordingEventStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider
) : RecordingEventStore {

    private val mutex = Mutex()

    override suspend fun append(event: RecordingEvent) = withContext(dispatchers.io) {
        val normalized = RecordingEvent(
            epochMs = event.epochMs.coerceAtLeast(0L),
            confidencePercent = event.confidencePercent.coerceIn(1, 100),
            source = event.source.trim().ifBlank { DEFAULT_SOURCE }.take(MAX_SOURCE_LENGTH)
        )
        mutex.withLock {
            val file = eventIndexFile()
            file.parentFile?.mkdirs()
            file.appendText(
                buildString {
                    append(normalized.epochMs)
                    append('\t')
                    append(normalized.confidencePercent)
                    append('\t')
                    append(normalized.source)
                    append('\n')
                }
            )
        }
    }

    override suspend fun query(
        fromEpochMs: Long?,
        toEpochMs: Long?,
        limit: Int
    ): List<RecordingEvent> = withContext(dispatchers.io) {
        val normalizedFrom = fromEpochMs?.coerceAtLeast(0L)
        val normalizedTo = toEpochMs?.coerceAtLeast(0L)
        val normalizedLimit = limit.coerceIn(1, MAX_QUERY_LIMIT)
        mutex.withLock {
            loadEventsLocked()
                .asSequence()
                .filter { event ->
                    val matchesFrom = normalizedFrom?.let { event.epochMs >= it } ?: true
                    val matchesTo = normalizedTo?.let { event.epochMs <= it } ?: true
                    matchesFrom && matchesTo
                }
                .sortedByDescending(RecordingEvent::epochMs)
                .take(normalizedLimit)
                .toList()
        }
    }

    override suspend fun pruneBefore(cutoffEpochMs: Long) = withContext(dispatchers.io) {
        mutex.withLock {
            val file = eventIndexFile()
            if (!file.exists()) return@withLock
            val retained = loadEventsLocked()
                .filter { it.epochMs >= cutoffEpochMs.coerceAtLeast(0L) }
            if (retained.isEmpty()) {
                file.writeText("")
            } else {
                file.writeText(
                    retained.joinToString(separator = "\n") { event ->
                        listOf(
                            event.epochMs.toString(),
                            event.confidencePercent.toString(),
                            event.source
                        ).joinToString("\t")
                    } + "\n"
                )
            }
        }
    }

    private fun loadEventsLocked(): List<RecordingEvent> {
        val file = eventIndexFile()
        if (!file.exists() || !file.isFile) return emptyList()
        return file.readLines()
            .mapNotNull(::parseLine)
    }

    private fun parseLine(line: String): RecordingEvent? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val epochMs = parts[0].toLongOrNull() ?: return null
        val confidence = parts[1].toIntOrNull() ?: return null
        val source = parts[2].trim()
        if (source.isBlank()) return null
        return RecordingEvent(
            epochMs = epochMs.coerceAtLeast(0L),
            confidencePercent = confidence.coerceIn(1, 100),
            source = source.take(MAX_SOURCE_LENGTH)
        )
    }

    private fun eventIndexFile(): File {
        val externalBase = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val recordingDir = if (externalBase != null) {
            File(externalBase, "ZCam/recordings")
        } else {
            File(context.filesDir, "zcam_recordings")
        }
        return File(recordingDir, EVENT_INDEX_FILE_NAME)
    }

    private companion object {
        const val EVENT_INDEX_FILE_NAME = "recording_events_v1.tsv"
        const val DEFAULT_SOURCE = "motion"
        const val MAX_SOURCE_LENGTH = 32
        const val MAX_QUERY_LIMIT = 1_000
    }
}
