package com.zcam.core.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RotatingFileLogSinkTest {

    @Test
    fun rotates_logs_when_file_size_limit_is_reached() {
        val root = Files.createTempDirectory("zcam-log-rotation").toFile()
        val logDir = File(root, "logs")
        var rotations = 0
        val sink = RotatingFileLogSink(
            logDirectory = logDir,
            fileName = "zcam.log",
            maxBytesPerFile = 120,
            maxFiles = 2,
            onRotation = { rotations += 1 }
        )

        repeat(20) { index ->
            sink.append(
                priority = 4,
                tag = "ZCam",
                message = "entry-$index-xxxxxxxxxxxxxxxxxxxxxxxx",
                throwable = null
            )
        }

        assertTrue("rotation callback should run", rotations > 0)
        assertTrue(File(logDir, "zcam.log").exists())
        assertTrue(File(logDir, "zcam.log.1").exists())
    }

    @Test
    fun keeps_only_configured_rotation_depth() {
        val root = Files.createTempDirectory("zcam-log-depth").toFile()
        val logDir = File(root, "logs")
        val sink = RotatingFileLogSink(
            logDirectory = logDir,
            fileName = "zcam.log",
            maxBytesPerFile = 100,
            maxFiles = 2
        )

        repeat(60) { index ->
            sink.append(
                priority = 5,
                tag = "ZCam",
                message = "line-$index-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                throwable = null
            )
        }

        val generated = logDir.listFiles()
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        assertEquals(listOf("zcam.log", "zcam.log.1", "zcam.log.2"), generated)
    }
}
