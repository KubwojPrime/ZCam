package com.zcam.watchdog

interface WatchdogManager {
    suspend fun start()
    suspend fun stop()
    fun signalHeartbeat(component: String)
}
