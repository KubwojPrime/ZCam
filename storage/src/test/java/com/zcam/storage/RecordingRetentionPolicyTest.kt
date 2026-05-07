package com.zcam.storage

import com.zcam.core.logging.ZCamLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecordingRetentionPolicyTest {

    @Test
    fun removes_oldest_segments_when_storage_limit_exceeded() {
        val root = Files.createTempDirectory("zcam-retention-test").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }

        val oldest = createSegment(recordingDir, "segment_1000.mp4", 12)
        createSegment(recordingDir, "segment_2000.mp4", 12)
        createSegment(recordingDir, "segment_3000.mp4", 12)

        val catalog = RecordingCatalog(
            segments = listOf(
                RecordingSegmentEntry(oldest.name, 1000L, 1010L, 12L),
                RecordingSegmentEntry("segment_2000.mp4", 2000L, 2010L, 12L),
                RecordingSegmentEntry("segment_3000.mp4", 3000L, 3010L, 12L)
            )
        )

        val outcome = RecordingRetentionPolicy().enforce(
            recordingDir = recordingDir,
            initialCatalog = catalog,
            maxStorageBytes = 24L,
            minFreeBytes = 1L,
            logger = TestLogger(),
            freeSpaceProvider = { Long.MAX_VALUE }
        )

        assertEquals(1, outcome.deletedSegments)
        assertEquals(24L, outcome.catalog.totalBytes)
        assertFalse(oldest.exists())
    }

    @Test
    fun removes_all_segments_when_free_space_remains_below_minimum() {
        val root = Files.createTempDirectory("zcam-space-test").toFile()
        val recordingDir = File(root, "recordings").apply { mkdirs() }

        val first = createSegment(recordingDir, "segment_1000.mp4", 12)
        val second = createSegment(recordingDir, "segment_2000.mp4", 12)
        val catalog = RecordingCatalog(
            segments = listOf(
                RecordingSegmentEntry("segment_1000.mp4", 1000L, 1010L, 12L),
                RecordingSegmentEntry("segment_2000.mp4", 2000L, 2010L, 12L)
            )
        )

        val outcome = RecordingRetentionPolicy().enforce(
            recordingDir = recordingDir,
            initialCatalog = catalog,
            maxStorageBytes = Long.MAX_VALUE,
            minFreeBytes = 10L,
            logger = TestLogger(),
            freeSpaceProvider = { 0L }
        )

        assertEquals(2, outcome.deletedSegments)
        assertTrue(outcome.catalog.segments.isEmpty())
        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    private fun createSegment(directory: File, fileName: String, sizeBytes: Int): File {
        return File(directory, fileName).apply {
            writeBytes(ByteArray(sizeBytes))
        }
    }

    private class TestLogger : ZCamLogger {
        override fun d(message: String) = Unit
        override fun i(message: String) = Unit
        override fun w(message: String) = Unit
        override fun e(throwable: Throwable?, message: String) = Unit
    }
}
