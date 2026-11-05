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
    private val queue = Channel<RuntimeControlCommand>(Channel.BUFFERED)
    val requests = queue.receiveAsFlow()

    override fun requestRestart(): Boolean {
        if (!restarting.compareAndSet(false, true)) return false
        if (queue.trySend(RuntimeControlCommand.Restart).isFailure) {
            restarting.set(false)
            return false
        }
        return true
    }


    override fun requestCameraEnabled(enabled: Boolean): Boolean =
        queue.trySend(RuntimeControlCommand.SetCameraEnabled(enabled)).isSuccess

    fun finishRestart() {
        restarting.set(false)
    }
}

sealed interface RuntimeControlCommand {
    data object Restart : RuntimeControlCommand
    data class SetCameraEnabled(val enabled: Boolean) : RuntimeControlCommand
}
