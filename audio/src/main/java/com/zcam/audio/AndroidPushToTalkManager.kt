package com.zcam.audio

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.domain.audio.PlaybackSource
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidPushToTalkManager @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : PushToTalkManager {

    private val engineStarted = AtomicBoolean(false)
    private val transmitting = AtomicBoolean(false)
    private val liveListening = AtomicBoolean(false)
    private val playingBack = AtomicBoolean(false)

    override suspend fun start() = withContext(dispatchers.io) {
        engineStarted.set(true)
        logger.i("Audio engine started")
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        engineStarted.set(false)
        transmitting.set(false)
        liveListening.set(false)
        playingBack.set(false)
        logger.i("Audio engine stopped")
    }

    override suspend fun isHealthy(): Boolean = withContext(dispatchers.io) {
        engineStarted.get()
    }

    override suspend fun beginPushToTalk() = withContext(dispatchers.io) {
        if (transmitting.compareAndSet(false, true)) {
            logger.d("PTT transmit begin")
        }
    }

    override suspend fun endPushToTalk() = withContext(dispatchers.io) {
        if (transmitting.compareAndSet(true, false)) {
            logger.d("PTT transmit end")
        }
    }

    override suspend fun beginLiveListen() = withContext(dispatchers.io) {
        if (liveListening.compareAndSet(false, true)) {
            logger.d("Audio live listen begin")
        }
    }

    override suspend fun stopLiveListen() = withContext(dispatchers.io) {
        if (liveListening.compareAndSet(true, false)) {
            logger.d("Audio live listen end")
        }
    }

    override suspend fun startPlayback(source: PlaybackSource) = withContext(dispatchers.io) {
        if (playingBack.compareAndSet(false, true)) {
            logger.d("Audio playback start from $source")
        }
    }

    override suspend fun stopPlayback() = withContext(dispatchers.io) {
        if (playingBack.compareAndSet(true, false)) {
            logger.d("Audio playback stop")
        }
    }
}
