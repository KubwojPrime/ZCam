package com.zcam.storage

import android.content.Context
import android.os.Environment
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.recording.RecordingEvent
import com.zcam.core.domain.recording.RecordingEventStore
import com.zcam.core.domain.recording.RecordingEventType
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

    override suspend fun append(event: RecordingEvent, frameJpeg: ByteArray?) = withContext(dispatchers.io) {
        val normalized = RecordingEvent(
            epochMs = event.epochMs.coerceAtLeast(0L),
            endedAtEpochMs = event.endedAtEpochMs.coerceAtLeast(event.epochMs.coerceAtLeast(0L)),
            confidencePercent = event.confidencePercent.coerceIn(1, 100),
            source = event.source.trim().ifBlank { DEFAULT_SOURCE }.take(MAX_SOURCE_LENGTH),
            type = event.type
        )
        mutex.withLock {
            val events = loadEventsLocked().toMutableList()
            val previous = events.lastOrNull()
            val merged = previous?.takeIf { canMergeEvents(it, normalized) }?.let { current ->
                current.copy(
                    endedAtEpochMs = maxOf(current.endedAtEpochMs, normalized.endedAtEpochMs),
                    confidencePercent = maxOf(current.confidencePercent, normalized.confidencePercent),
                    source = if (normalized.confidencePercent >= current.confidencePercent) normalized.source else current.source
                )
            }
            val frameEpochMs = merged?.epochMs ?: normalized.epochMs
            val shouldReplaceFrame = merged == null ||
                normalized.confidencePercent >= (previous?.confidencePercent ?: 0)
            if (frameJpeg != null && frameJpeg.size in 4..MAX_FRAME_BYTES &&
                frameJpeg[0] == 0xff.toByte() && frameJpeg[1] == 0xd8.toByte() && shouldReplaceFrame
            ) {
                runCatching {
                    val frameFile = eventFrameFile(frameEpochMs)
                    frameFile.parentFile?.mkdirs()
                    val tempFile = File(frameFile.parentFile, "${frameFile.name}.tmp")
                    tempFile.writeBytes(frameJpeg)
                    if (frameFile.exists()) frameFile.delete()
                    if (!tempFile.renameTo(frameFile)) {
                        tempFile.copyTo(frameFile, overwrite = true)
                        tempFile.delete()
                    }
                    pruneExcessFramesLocked()
                }
            }
            if (merged != null) events[events.lastIndex] = merged else events += normalized
            writeEventsLocked(events)
        }
    }

    override suspend fun readFrame(epochMs: Long): ByteArray? = withContext(dispatchers.io) {
        if (epochMs <= 0L) return@withContext null
        mutex.withLock {
            val file = eventFrameFile(epochMs)
            if (file.isFile && file.length() in 1..MAX_FRAME_BYTES.toLong()) file.readBytes() else null
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
                    val matchesFrom = normalizedFrom?.let { event.endedAtEpochMs >= it } ?: true
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
                .filter { it.endedAtEpochMs >= cutoffEpochMs.coerceAtLeast(0L) }
            writeEventsLocked(retained)
            val retainedFrameEpochs = retained.mapTo(mutableSetOf(), RecordingEvent::epochMs)
            eventFramesDir().listFiles()?.forEach { frame ->
                val frameEpochMs = frame.nameWithoutExtension.toLongOrNull()
                if (frame.extension == "jpg" && frameEpochMs != null &&
                    frameEpochMs !in retainedFrameEpochs && frameEpochMs < cutoffEpochMs
                ) {
                    frame.delete()
                }
            }
        }
    }

    private fun pruneExcessFramesLocked() {
        val frames = eventFramesDir().listFiles()
            ?.filter { it.isFile && it.extension == "jpg" }
            ?.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE }
            .orEmpty()
        frames.take((frames.size - MAX_STORED_FRAMES).coerceAtLeast(0)).forEach(File::delete)
    }

    private fun eventFramesDir(): File = File(context.filesDir, "zcam_event_frames")

    private fun eventFrameFile(epochMs: Long): File = File(eventFramesDir(), "$epochMs.jpg")

    private fun loadEventsLocked(): List<RecordingEvent> {
        val file = eventIndexFile()
        if (!file.exists() || !file.isFile) return emptyList()
        return coalesceRecordingEvents(file.readLines()
            .mapNotNull(::parseLine)
            .sortedBy(RecordingEvent::epochMs))
    }

    private fun writeEventsLocked(events: List<RecordingEvent>) {
        val file = eventIndexFile()
        file.parentFile?.mkdirs()
        file.writeText(
            events.joinToString(separator = "\n") { event ->
                listOf(
                    event.epochMs.toString(),
                    event.confidencePercent.toString(),
                    event.source,
                    event.type.wireName,
                    event.endedAtEpochMs.toString()
                ).joinToString("\t")
            }.let { content -> if (content.isBlank()) "" else "$content\n" }
        )
    }

    private fun parseLine(line: String): RecordingEvent? {
        val parts = line.split('\t')
        if (parts.size < 3) return null
        val epochMs = parts[0].toLongOrNull() ?: return null
        val confidence = parts[1].toIntOrNull() ?: return null
        val source = parts[2].trim()
        if (source.isBlank()) return null
        val type = parts.getOrNull(3)
            ?.let(RecordingEventType::fromWireName)
            ?: RecordingEventType.fromSource(source)
        val endedAtEpochMs = parts.getOrNull(4)?.toLongOrNull()?.coerceAtLeast(epochMs) ?: epochMs
        return RecordingEvent(
            epochMs = epochMs.coerceAtLeast(0L),
            endedAtEpochMs = endedAtEpochMs.coerceAtLeast(0L),
            confidencePercent = confidence.coerceIn(1, 100),
            source = source.take(MAX_SOURCE_LENGTH),
            type = type
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
        const val MAX_FRAME_BYTES = 1_500_000
        const val MAX_STORED_FRAMES = 200
    }
}

internal fun coalesceRecordingEvents(events: List<RecordingEvent>): List<RecordingEvent> {
    if (events.isEmpty()) return emptyList()
    val merged = ArrayList<RecordingEvent>(events.size)
    events.sortedBy(RecordingEvent::epochMs).forEach { event ->
        val previous = merged.lastOrNull()
        if (previous != null && canMergeEvents(previous, event)) {
            merged[merged.lastIndex] = previous.copy(
                endedAtEpochMs = maxOf(previous.endedAtEpochMs, event.endedAtEpochMs),
                confidencePercent = maxOf(previous.confidencePercent, event.confidencePercent),
                source = if (event.confidencePercent >= previous.confidencePercent) event.source else previous.source
            )
        } else {
            merged += event
        }
    }
    return merged
}

private fun canMergeEvents(previous: RecordingEvent, next: RecordingEvent): Boolean =
    previous.type == next.type &&
        next.epochMs >= previous.epochMs &&
        next.epochMs - previous.endedAtEpochMs <= EVENT_MERGE_GAP_MS

private const val EVENT_MERGE_GAP_MS = 30_000L
