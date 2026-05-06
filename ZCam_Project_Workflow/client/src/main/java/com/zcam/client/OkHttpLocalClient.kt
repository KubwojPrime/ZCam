package com.zcam.client

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OkHttpLocalClient @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val logger: ZCamLogger
) : LocalClient {

    private val client = OkHttpClient()

    override suspend fun isServerAlive(host: String, port: Int): Boolean = withContext(dispatchers.io) {
        val request = Request.Builder()
            .url("http://$host:$port/health")
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        }.onFailure { error ->
            logger.w("Client ping failed: ${error.message}")
        }.getOrDefault(false)
    }
}
