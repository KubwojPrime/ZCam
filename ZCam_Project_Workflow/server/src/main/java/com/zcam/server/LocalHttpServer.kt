package com.zcam.server

interface LocalHttpServer {
    suspend fun start()
    suspend fun stop()
}
