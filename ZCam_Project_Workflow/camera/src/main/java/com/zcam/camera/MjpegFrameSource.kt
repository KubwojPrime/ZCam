package com.zcam.camera

interface MjpegFrameSource {
    fun latestFrame(): ByteArray
}
