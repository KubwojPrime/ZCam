package com.zcam.storage

import com.zcam.core.logging.ZCamLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    private class NoopLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
