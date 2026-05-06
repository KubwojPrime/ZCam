package com.zcam.audio

interface PushToTalkManager {
    suspend fun start()
    suspend fun stop()
    suspend fun beginTransmit()
    suspend fun endTransmit()
}
