package com.zcam.service

import com.zcam.audio.PushToTalkManager
import com.zcam.camera.CameraRuntime
import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import com.zcam.server.LocalHttpServer
import com.zcam.storage.LoopRecordingManager
import com.zcam.watchdog.WatchdogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZCamRuntimeCoordinator @Inject constructor(
    private val cameraRuntime: CameraRuntime,
    private val localHttpServer: LocalHttpServer,
    private val pushToTalkManager: PushToTalkManager,
    private val loopRecordingManager: LoopRecordingManager,
    private val watchdogManager: WatchdogManager,
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) {

    private val runtimeScope = CoroutineScope(SupervisorJob() + dispatchers.default)
    private var heartbeatJob: Job? = null

    suspend fun start() = withContext(dispatchers.io) {
        logger.i("Runtime start sequence")

        watchdogManager.start()
        cameraRuntime.start()
        pushToTalkManager.start()
        loopRecordingManager.start()
        localHttpServer.start()

        if (heartbeatJob?.isActive != true) {
            heartbeatJob = runtimeScope.launch {
                while (isActive) {
                    watchdogManager.signalHeartbeat("camera")
                    watchdogManager.signalHeartbeat("server")
                    watchdogManager.signalHeartbeat("audio")
                    watchdogManager.signalHeartbeat("storage")
                    delay(2_000L)
                }
            }
        }

        logger.i("Runtime started")
    }

    suspend fun stop() = withContext(dispatchers.io) {
        logger.i("Runtime stop sequence")

        heartbeatJob?.cancel()
        heartbeatJob = null

        localHttpServer.stop()
        loopRecordingManager.stop()
        pushToTalkManager.stop()
        cameraRuntime.stop()
        watchdogManager.stop()

        logger.i("Runtime stopped")
    }
}
