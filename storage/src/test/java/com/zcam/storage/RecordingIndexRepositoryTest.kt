package com.zcam.storage

import com.zcam.core.logging.ZCamLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingIndexRepositoryTest {

    @Test
    fun rebuilds_index_when_existing_index_is_corrupted() {
        val root = Files.createTempDirectory("zcam-index-test").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }

        val first = File(recordingDir, "segment_1000.mp4").apply { writeBytes(ByteArray(16)) }
        File(recordingDir, "segment_2000.mp4").apply { writeBytes(ByteArray(24)) }
        val indexFile = File(recordingDir, "recording_index_v1.tsv").apply {
            writeText("corrupted_index")
        }

        val repository = RecordingIndexRepository(indexFile)
        val catalog = repository.loadOrRebuild(recordingDir, TestLogger())

        assertEquals(2, catalog.segments.size)
        assertEquals(first.name, catalog.segments.first().fileName)
        assertTrue(indexFile.readText().startsWith("ZCAM_RECORDING_INDEX_V1"))
    }

    @Test
    fun restores_segments_from_disk_when_index_missing_after_restart() {
        val root = Files.createTempDirectory("zcam-restart-test").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }

        File(recordingDir, "segment_3000.mp4").apply { writeBytes(ByteArray(8)) }
        val earliest = File(recordingDir, "segment_1000.mp4").apply { writeBytes(ByteArray(6)) }
        File(recordingDir, "segment_2000.mp4").apply { writeBytes(ByteArray(7)) }
        val indexFile = File(recordingDir, "recording_index_v1.tsv")

        val repository = RecordingIndexRepository(indexFile)
        val catalog = repository.loadOrRebuild(recordingDir, TestLogger())

        assertEquals(3, catalog.segments.size)
        assertEquals(earliest.name, catalog.segments.first().fileName)
        assertTrue(indexFile.exists())
    }

    private class TestLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
