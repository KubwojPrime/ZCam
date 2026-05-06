package com.zcam.core.logging

fun ZCamLogger.d(eventId: LogEventId, message: String) {
    d("[${eventId.code}] $message")
}

fun ZCamLogger.i(eventId: LogEventId, message: String) {
    i("[${eventId.code}] $message")
}

fun ZCamLogger.w(eventId: LogEventId, message: String) {
    w("[${eventId.code}] $message")
}

fun ZCamLogger.e(eventId: LogEventId, throwable: Throwable? = null, message: String) {
    e(throwable, "[${eventId.code}] $message")
}
