package com.zcam.core.logging

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimberZCamLogger @Inject constructor() : ZCamLogger {
    override fun d(message: String) = Timber.tag(TAG).d(message)

    override fun i(message: String) = Timber.tag(TAG).i(message)

    override fun w(message: String) = Timber.tag(TAG).w(message)

    override fun e(throwable: Throwable?, message: String) {
        if (throwable != null) {
            Timber.tag(TAG).e(throwable, message)
        } else {
            Timber.tag(TAG).e(message)
        }
    }

    private companion object {
        private const val TAG = "ZCam"
    }
}
