package com.zcam.core.logging

interface ZCamLogger {
    fun d(message: String)
    fun i(message: String)
    fun w(message: String)
    fun e(throwable: Throwable? = null, message: String)
}
