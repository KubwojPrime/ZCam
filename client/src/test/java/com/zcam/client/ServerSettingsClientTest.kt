package com.zcam.client

import com.zcam.core.dispatchers.DispatcherProvider
import com.zcam.core.logging.ZCamLogger
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSettingsClientTest {
    @Test
    fun malformed_server_address_returns_a_failure_instead_of_crashing_the_form() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = OkHttpLocalClient(object : DispatcherProvider {
            override val io = dispatcher
            override val default = dispatcher
        }, object : ZCamLogger {
            override fun d(message: String) = Unit
            override fun i(message: String) = Unit
            override fun w(message: String) = Unit
            override fun e(throwable: Throwable?, message: String) = Unit
        })
        val result = client.fetchServerSettings(ClientTarget("bad host /", 8080, "token", "device"))
        assertEquals("invalid_target", (result as ClientCallResult.Failure).reason)
    }
}
