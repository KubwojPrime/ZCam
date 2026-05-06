package com.zcam.storage

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class RecordingPolicy(
    val segmentLength: Duration = 5.minutes,
    val maxStorageBytes: Long = 32L * 1024L * 1024L * 1024L,
    val minFreeBytes: Long = 5L * 1024L * 1024L * 1024L
)
