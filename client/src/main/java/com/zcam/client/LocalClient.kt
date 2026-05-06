package com.zcam.client

interface LocalClient {
    suspend fun isServerAlive(host: String, port: Int): Boolean
}
