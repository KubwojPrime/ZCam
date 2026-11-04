package com.zcam.storage

import com.zcam.core.logging.LogEventId
import com.zcam.core.logging.ZCamLogger
import com.zcam.core.logging.i
import com.zcam.core.logging.w
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

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

    fun recoverInterruptedSegments(recordingDir: File, logger: ZCamLogger) {
        recordingDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(PARTIAL_SEGMENT_SUFFIX) }
            ?.forEach { partial ->
                if (isFinalizedMp4(partial)) {
                    val finalFile = File(recordingDir, partial.name.removeSuffix(PARTIAL_SEGMENT_SUFFIX) + ".mp4")
                    promoteFile(partial, finalFile)
                    logger.i(
                        LogEventId.RECORDING_INDEX_REBUILT,
                        "Recovered finalized recording segment ${finalFile.name} after interrupted indexing"
                    )
                } else {
                    if (!partial.delete()) {
                        logger.w(
                            LogEventId.RECORDING_INDEX_CORRUPTED,
                            "Unable to remove incomplete recording segment ${partial.name}"
                        )
                    } else {
                        logger.w(
                            LogEventId.RECORDING_INDEX_CORRUPTED,
                            "Removed incomplete recording segment ${partial.name} after interrupted recording"
                        )
                    }
                }
            }
    }

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
            ?.filter(::isFinalSegmentFile)
            ?.associateBy { it.name }
            .orEmpty()

        val filtered = parsed.segments
            .filter { availableFiles.containsKey(it.fileName) }
            .map { entry ->
                val file = availableFiles.getValue(entry.fileName)
                entry.copy(sizeBytes = file.length())
            }

        val indexedNames = filtered.mapTo(HashSet(), RecordingSegmentEntry::fileName)
        val recovered = availableFiles.values
            .filterNot { indexedNames.contains(it.name) }
            .map(::entryFromFile)

        return if (filtered.size != parsed.segments.size || recovered.isNotEmpty()) {
            val normalized = RecordingCatalog((filtered + recovered).sortedBy { it.startedAtEpochMs })
            save(normalized)
            logger.w(
                LogEventId.RECORDING_INDEX_CORRUPTED,
                "Recording index reconciled with segment files; recovered=${recovered.size}"
            )
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
            ?.filter(::isFinalSegmentFile)
            ?.sortedBy { it.lastModified() }
            ?.map(::entryFromFile)
            ?.sortedBy { it.startedAtEpochMs }
            .orEmpty()

        return RecordingCatalog(segments)
    }

    private fun parseStartEpochMs(file: File): Long? {
        val name = file.nameWithoutExtension
        if (!name.startsWith("segment_")) return null
        return name.removePrefix("segment_").toLongOrNull()
    }

    private fun entryFromFile(file: File): RecordingSegmentEntry {
        val startedAt = parseStartEpochMs(file) ?: file.lastModified()
        val endedAt = maxOf(startedAt + 1, file.lastModified())
        return RecordingSegmentEntry(
            fileName = file.name,
            startedAtEpochMs = startedAt,
            endedAtEpochMs = endedAt,
            sizeBytes = file.length(),
            container = "MP4",
            codec = "H264"
        )
    }

    private fun isFinalSegmentFile(file: File): Boolean {
        return file.isFile && FINAL_SEGMENT_FILE_REGEX.matches(file.name)
    }

    private companion object {
        const val HEADER = "ZCAM_RECORDING_INDEX_V1"
        const val FIELD_DELIMITER = "\t"
        const val PARTIAL_SEGMENT_SUFFIX = ".partial.mp4"
        val FINAL_SEGMENT_FILE_REGEX = Regex("^segment_[0-9]+\\.mp4$")
    }
}

internal fun promoteFile(source: File, destination: File) {
    destination.parentFile?.mkdirs()
    if (destination.exists() && !destination.delete()) {
        throw IllegalStateException("Failed to replace ${destination.absolutePath}")
    }
    if (!source.renameTo(destination)) {
        source.copyTo(destination, overwrite = true)
        RandomAccessFile(destination, "rw").use { it.fd.sync() }
        if (!source.delete()) {
            throw IllegalStateException("Failed to remove ${source.absolutePath} after copy")
        }
    }
}

internal fun isFinalizedMp4(file: File): Boolean = runCatching {
    if (!file.isFile || file.length() < 16L) return@runCatching false
    RandomAccessFile(file, "r").use { input ->
        val fileLength = input.length()
        var offset = 0L
        var boxesRead = 0
        var hasFileType = false
        var hasMovieBox = false
        while (offset + 8L <= fileLength && boxesRead < 10_000) {
            input.seek(offset)
            val size32 = input.readInt().toLong() and 0xffffffffL
            val typeBytes = ByteArray(4)
            input.readFully(typeBytes)
            val type = String(typeBytes, StandardCharsets.US_ASCII)
            val headerSize: Long
            val boxSize = when (size32) {
                0L -> {
                    headerSize = 8L
                    fileLength - offset
                }
                1L -> {
                    if (offset + 16L > fileLength) return@use false
                    headerSize = 16L
                    input.readLong()
                }
                else -> {
                    headerSize = 8L
                    size32
                }
            }
            if (boxSize < headerSize || boxSize > fileLength - offset) return@use false
            if (type == "ftyp") hasFileType = true
            if (type == "moov") hasMovieBox = true
            offset += boxSize
            boxesRead += 1
        }
        hasFileType && hasMovieBox && offset == fileLength
    }
}.getOrDefault(false)

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

internal fun estimatedNextSegmentReserveBytes(catalog: RecordingCatalog): Long {
    val recentLargest = catalog.sortedOldestFirst()
        .takeLast(3)
        .maxOfOrNull(RecordingSegmentEntry::sizeBytes)
        ?: return MIN_SEGMENT_RESERVE_BYTES
    return (recentLargest + recentLargest / 4L)
        .coerceIn(MIN_SEGMENT_RESERVE_BYTES, MAX_SEGMENT_RESERVE_BYTES)
}

private const val MIN_SEGMENT_RESERVE_BYTES = 256L * 1024L * 1024L
private const val MAX_SEGMENT_RESERVE_BYTES = 1024L * 1024L * 1024L
