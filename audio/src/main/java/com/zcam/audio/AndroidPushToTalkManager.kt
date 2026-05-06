package com.zcam.audio

import com.zcam.core.dispatchers.DispatcherProvider
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

    private val transmitting = AtomicBoolean(false)

    override suspend fun start() = withContext(dispatchers.io) {
        logger.i("Push-to-talk audio engine started")
    }

    override suspend fun stop() = withContext(dispatchers.io) {
        transmitting.set(false)
        logger.i("Push-to-talk audio engine stopped")
    }

    override suspend fun beginTransmit() = withContext(dispatchers.io) {
        if (transmitting.compareAndSet(false, true)) {
            logger.d("PTT transmit begin")
        }
    }

    override suspend fun endTransmit() = withContext(dispatchers.io) {
        if (transmitting.compareAndSet(true, false)) {
            logger.d("PTT transmit end")
        }
    }
}
