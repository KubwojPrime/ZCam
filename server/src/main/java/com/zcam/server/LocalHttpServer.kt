package com.zcam.server

interface LocalHttpServer {
    suspend fun start(port: Int)
    suspend fun stop()
    suspend fun isHealthy(): Boolean
}
