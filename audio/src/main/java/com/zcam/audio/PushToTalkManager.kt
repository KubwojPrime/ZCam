package com.zcam.audio

import com.zcam.core.domain.audio.AudioEngine

interface PushToTalkManager : AudioEngine {
    suspend fun isHealthy(): Boolean
}
