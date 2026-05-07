package com.zcam.storage

import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import java.io.File

internal data class RecordingSegmentEntry(
    val fileName: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val sizeBytes: Long,
    val container: String = "MP4",
    val codec: String = "H264"
)

internal data class RecordingCatalog(
    val segments: List<RecordingSegmentEntry>
) {
    val totalBytes: Long = segments.sumOf { it.sizeBytes }

    fun sortedOldestFirst(): List<RecordingSegmentEntry> = segments.sortedBy { it.startedAtEpochMs }
}

internal data class RetentionOutcome(
    val catalog: RecordingCatalog,
    val deletedSegments: Int,
    val deletedBytes: Long
)

internal class RecordingIndexRepository(
    private val indexFile: File
) {

    fun loadOrRebuild(recordingDir: File, logger: ZCamLogger): RecordingCatalog {
        if (!indexFile.exists()) {
            val rebuilt = rebuildFromDirectory(recordingDir)
            save(rebuilt)
            logger.i(LogEventId.RECORDING_INDEX_REBUILT, "Recording index created from existing segments")
            return rebuilt
        }

        val parsed = runCatching { parse(indexFile.readText()) }
            .getOrElse { error ->
                logger.w(LogEventId.RECORDING_INDEX_CORRUPTED, "Recording index corrupted: ${error.message}")
                val rebuilt = rebuildFromDirectory(recordingDir)
                save(rebuilt)
                logger.i(LogEventId.RECORDING_INDEX_REBUILT, "Recording index rebuilt after corruption")
                return rebuilt
            }

        val availableFiles = recordingDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?.associateBy { it.name }
            .orEmpty()

        val filtered = parsed.segments
            .filter { availableFiles.containsKey(it.fileName) }
            .map { entry ->
                val file = availableFiles.getValue(entry.fileName)
                entry.copy(sizeBytes = file.length())
            }

        return if (filtered.size != parsed.segments.size) {
            val normalized = RecordingCatalog(filtered.sortedBy { it.startedAtEpochMs })
            save(normalized)
            logger.w(LogEventId.RECORDING_INDEX_CORRUPTED, "Recording index normalized to available segment files")
            normalized
        } else {
            RecordingCatalog(filtered.sortedBy { it.startedAtEpochMs })
        }
    }

    fun save(catalog: RecordingCatalog) {
        indexFile.parentFile?.mkdirs()
        val tempFile = File(indexFile.parentFile, "${indexFile.name}.tmp")
        tempFile.writeText(serialize(catalog))
        if (indexFile.exists() && !indexFile.delete()) {
            throw IllegalStateException("Failed to replace recording index file ${indexFile.absolutePath}")
        }
        if (!tempFile.renameTo(indexFile)) {
            tempFile.copyTo(indexFile, overwrite = true)
            tempFile.delete()
        }
    }

    private fun parse(raw: String): RecordingCatalog {
        val lines = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        require(lines.isNotEmpty()) { "Missing index header." }
        require(lines.first() == HEADER) { "Unsupported index header." }

        val entries = lines.drop(1).map { line ->
            val fields = line.split(FIELD_DELIMITER)
            require(fields.size >= 6) { "Invalid index row: $line" }
            RecordingSegmentEntry(
                fileName = fields[0],
                startedAtEpochMs = fields[1].toLong(),
                endedAtEpochMs = fields[2].toLong(),
                sizeBytes = fields[3].toLong(),
                container = fields[4],
                codec = fields[5]
            )
        }.sortedBy { it.startedAtEpochMs }

        return RecordingCatalog(entries)
    }

    private fun serialize(catalog: RecordingCatalog): String {
        val body = catalog.sortedOldestFirst().joinToString(separator = "\n") { segment ->
            listOf(
                segment.fileName,
                segment.startedAtEpochMs.toString(),
                segment.endedAtEpochMs.toString(),
                segment.sizeBytes.toString(),
                segment.container,
                segment.codec
            ).joinToString(separator = FIELD_DELIMITER)
        }
        return if (body.isEmpty()) {
            "$HEADER\n"
        } else {
            "$HEADER\n$body\n"
        }
    }

    private fun rebuildFromDirectory(recordingDir: File): RecordingCatalog {
        val segments = recordingDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
            ?.sortedBy { it.lastModified() }
            ?.map { file ->
                val startedAt = parseStartEpochMs(file) ?: file.lastModified()
                val endedAt = maxOf(startedAt + 1, file.lastModified())
                RecordingSegmentEntry(
                    fileName = file.name,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = endedAt,
                    sizeBytes = file.length(),
                    container = "MP4",
                    codec = "H264"
                )
            }
            ?.sortedBy { it.startedAtEpochMs }
            .orEmpty()

        return RecordingCatalog(segments)
    }

    private fun parseStartEpochMs(file: File): Long? {
        val name = file.nameWithoutExtension
        if (!name.startsWith("segment_")) return null
        return name.removePrefix("segment_").toLongOrNull()
    }

    private companion object {
        const val HEADER = "ZCAM_RECORDING_INDEX_V1"
        const val FIELD_DELIMITER = "\t"
    }
}

internal class RecordingRetentionPolicy {

    fun enforce(
        recordingDir: File,
        initialCatalog: RecordingCatalog,
        maxStorageBytes: Long,
        minFreeBytes: Long,
        logger: ZCamLogger,
        freeSpaceProvider: () -> Long = { recordingDir.usableSpace }
    ): RetentionOutcome {
        var currentCatalog = initialCatalog
        var deletedSegments = 0
        var deletedBytes = 0L

        val ordered = currentCatalog.sortedOldestFirst().toMutableList()
        var currentTotalBytes = ordered.sumOf { it.sizeBytes }

        while (ordered.isNotEmpty() && (currentTotalBytes > maxStorageBytes || freeSpaceProvider() < minFreeBytes)) {
            val oldest = ordered.first()
            val file = File(recordingDir, oldest.fileName)
            val fileBytes = if (file.exists()) file.length() else oldest.sizeBytes
            val removed = if (file.exists()) file.delete() else true
            if (!removed) {
                logger.w(
                    LogEventId.RECORDING_CLEANUP_FAILED,
                    "Failed to remove segment ${oldest.fileName}; retention will retry after recovery"
                )
                break
            }
            ordered.removeAt(0)
            deletedSegments += 1
            deletedBytes += fileBytes
            currentTotalBytes = (currentTotalBytes - oldest.sizeBytes).coerceAtLeast(0L)
            logger.w(
                LogEventId.RECORDING_CLEANUP_REMOVED,
                "Removed segment ${oldest.fileName} (${fileBytes} bytes) due to storage policy"
            )
        }

        currentCatalog = RecordingCatalog(ordered)
        return RetentionOutcome(
            catalog = currentCatalog,
            deletedSegments = deletedSegments,
            deletedBytes = deletedBytes
        )
    }
}

internal fun bytesFromGb(gb: Int): Long = gb.toLong() * 1024L * 1024L * 1024L
