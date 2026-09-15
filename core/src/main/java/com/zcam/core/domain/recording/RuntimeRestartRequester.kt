package com.zcam.core.domain.recording

interface RuntimeRestartRequester {
    fun requestRestart(): Boolean
}
