package com.zcam.storage

import com.zcam.core.logging.ZCamLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files

class RecordingIndexRepositoryRecoveryTest {

    @Test
    fun rebuilds_index_when_corrupted() {
        val root = Files.createTempDirectory("zcam-index-corrupted").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }
        File(recordingDir, "segment_1000.mp4").writeBytes(ByteArray(16))
        File(recordingDir, "segment_2000.mp4").writeBytes(ByteArray(32))

        val indexFile = File(recordingDir, "recording_index_v1.tsv")
        indexFile.writeText("corrupted\nnot-a-valid-row")
        val repository = RecordingIndexRepository(indexFile)

        val catalog = repository.loadOrRebuild(recordingDir, NoopLogger())

        assertEquals(2, catalog.segments.size)
        assertTrue(indexFile.readText().startsWith("ZCAM_RECORDING_INDEX_V1"))
    }

    @Test
    fun recreates_index_when_missing_after_restart() {
        val root = Files.createTempDirectory("zcam-index-missing").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }
        File(recordingDir, "segment_3000.mp4").writeBytes(ByteArray(20))

        val indexFile = File(recordingDir, "recording_index_v1.tsv")
        val repository = RecordingIndexRepository(indexFile)

        val catalog = repository.loadOrRebuild(recordingDir, NoopLogger())

        assertEquals(1, catalog.segments.size)
        assertTrue(indexFile.exists())
    }

    @Test
    fun adds_finalized_segment_missing_from_valid_index() {
        val root = Files.createTempDirectory("zcam-index-orphan").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }
        val indexFile = File(recordingDir, "recording_index_v1.tsv")
        indexFile.writeText("ZCAM_RECORDING_INDEX_V1\n")
        File(recordingDir, "segment_4000.mp4").writeBytes(ByteArray(24))

        val catalog = RecordingIndexRepository(indexFile).loadOrRebuild(recordingDir, NoopLogger())

        assertEquals(listOf("segment_4000.mp4"), catalog.segments.map { it.fileName })
    }

    @Test
    fun promotes_finalized_partial_segment_after_restart() {
        val root = Files.createTempDirectory("zcam-index-partial-finalized").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }
        val partial = File(recordingDir, "segment_5000.partial.mp4")
        writeMinimalFinalizedMp4(partial)
        val repository = RecordingIndexRepository(File(recordingDir, "recording_index_v1.tsv"))

        repository.recoverInterruptedSegments(recordingDir, NoopLogger())
        val catalog = repository.loadOrRebuild(recordingDir, NoopLogger())

        assertTrue(File(recordingDir, "segment_5000.mp4").exists())
        assertEquals(listOf("segment_5000.mp4"), catalog.segments.map { it.fileName })
    }

    @Test
    fun removes_incomplete_partial_segment_after_restart() {
        val root = Files.createTempDirectory("zcam-index-partial-incomplete").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }
        val partial = File(recordingDir, "segment_6000.partial.mp4").apply {
            writeBytes(ByteArray(128))
        }
        val repository = RecordingIndexRepository(File(recordingDir, "recording_index_v1.tsv"))

        repository.recoverInterruptedSegments(recordingDir, NoopLogger())

        assertTrue(!partial.exists())
    }

    private fun writeMinimalFinalizedMp4(file: File) {
        FileOutputStream(file).use { output ->
            listOf("ftyp", "mdat", "moov").forEach { type ->
                output.write(ByteBuffer.allocate(4).putInt(8).array())
                output.write(type.toByteArray(Charsets.US_ASCII))
            }
        }
    }

    private class NoopLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
