package com.zcam.service

import com.zcam.core.domain.recording.RuntimeRestartRequester
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZCamRuntimeRestarter @Inject constructor() : RuntimeRestartRequester {
    private val restarting = AtomicBoolean(false)
    private val queue = Channel<Unit>(Channel.CONFLATED)
    val requests = queue.receiveAsFlow()

    override fun requestRestart(): Boolean {
        if (!restarting.compareAndSet(false, true)) return false
        if (queue.trySend(Unit).isFailure) {
            restarting.set(false)
            return false
        }
        return true
    }

    fun finishRestart() {
        restarting.set(false)
    }
}
