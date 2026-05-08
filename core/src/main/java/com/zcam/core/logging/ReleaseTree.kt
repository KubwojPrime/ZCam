package com.zcam.core.logging

import android.content.Context
import timber.log.Timber
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ReleaseTree(
    context: Context,
    maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
    maxFiles: Int = DEFAULT_MAX_FILES
) : Timber.Tree() {

    private val fileWriterExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "zcam-log-writer").apply { isDaemon = true }
    }

    private val sink = RotatingFileLogSink(
        logDirectory = File(context.filesDir, "logs"),
        fileName = LOG_FILE_NAME,
        maxBytesPerFile = maxBytesPerFile,
        maxFiles = maxFiles,
        onRotation = {
            android.util.Log.i("ZCam", "[${LogEventId.LOG_ROTATED.code}] rotated persistent logs")
        },
        onWriteFailure = { error ->
            android.util.Log.w(
                "ZCam",
                "[${LogEventId.LOG_PERSIST_FAILED.code}] failed to persist log: ${error.message}"
            )
        }
    )

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val safeTag = tag ?: "ZCam"
        if (t == null) {
            android.util.Log.println(priority, safeTag, message)
        } else {
            android.util.Log.println(priority, safeTag, "$message\n${android.util.Log.getStackTraceString(t)}")
        }
        fileWriterExecutor.execute {
            sink.append(priority, safeTag, message, t)
        }
    }

    private companion object {
        const val LOG_FILE_NAME = "zcam.log"
        const val DEFAULT_MAX_BYTES_PER_FILE = 2L * 1024L * 1024L
        const val DEFAULT_MAX_FILES = 4
    }
}
