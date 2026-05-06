package com.zcam.service.runtime

import kotlinx.coroutines.delay
import javax.inject.Inject

interface RetryBackoffScheduler {
    suspend fun pause(millis: Long)
}

class DefaultRetryBackoffScheduler @Inject constructor() : RetryBackoffScheduler {
    override suspend fun pause(millis: Long) {
        delay(millis)
    }
}
