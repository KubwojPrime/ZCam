package com.zcam.core.logging

import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class RotatingFileLogSink(
    private val logDirectory: File,
    private val fileName: String,
    private val maxBytesPerFile: Long,
    private val maxFiles: Int,
    private val onRotation: (() -> Unit)? = null,
    private val onWriteFailure: ((Throwable) -> Unit)? = null
) {

    @Synchronized
    fun append(priority: Int, tag: String, message: String, throwable: Throwable?) {
        runCatching {
            logDirectory.mkdirs()

            val rendered = buildLine(priority, tag, message, throwable)
            val bytes = rendered.toByteArray(StandardCharsets.UTF_8)
            val activeFile = File(logDirectory, fileName)

            if (activeFile.exists() && activeFile.length() + bytes.size > maxBytesPerFile) {
                rotateFiles(activeFile)
                onRotation?.invoke()
            }

            activeFile.appendText(rendered, Charsets.UTF_8)
        }.onFailure { error ->
            onWriteFailure?.invoke(error)
        }
    }

    private fun rotateFiles(activeFile: File) {
        for (index in maxFiles downTo 1) {
            val source = if (index == 1) activeFile else File(logDirectory, "$fileName.${index - 1}")
            if (!source.exists()) continue
            val target = File(logDirectory, "$fileName.$index")
            if (target.exists()) {
                target.delete()
            }
            source.renameTo(target)
        }
    }

    private fun buildLine(priority: Int, tag: String, message: String, throwable: Throwable?): String {
        val timestamp = timestampFormat.format(Date())
        val level = when (priority) {
            PRIORITY_VERBOSE -> "V"
            PRIORITY_DEBUG -> "D"
            PRIORITY_INFO -> "I"
            PRIORITY_WARN -> "W"
            PRIORITY_ERROR -> "E"
            PRIORITY_ASSERT -> "A"
            else -> "?"
        }
        val base = "$timestamp $level/$tag: $message"
        return if (throwable == null) {
            "$base\n"
        } else {
            "$base\n${throwable.stackTraceToString()}\n"
        }
    }

    private companion object {
        val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        const val PRIORITY_VERBOSE = 2
        const val PRIORITY_DEBUG = 3
        const val PRIORITY_INFO = 4
        const val PRIORITY_WARN = 5
        const val PRIORITY_ERROR = 6
        const val PRIORITY_ASSERT = 7
    }
}
