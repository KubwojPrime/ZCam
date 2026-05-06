package com.zcam.core.domain.config

data class LoopRecordingConfig(
    val segmentMinutes: Int = 5,
    val maxStorageGb: Int = 32,
    val minFreeStorageGb: Int = 5
)
