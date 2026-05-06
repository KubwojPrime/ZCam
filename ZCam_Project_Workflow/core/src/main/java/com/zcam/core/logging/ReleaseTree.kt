package com.zcam.core.logging

import timber.log.Timber

class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (t == null) {
            android.util.Log.println(priority, tag ?: "ZCam", message)
        } else {
            android.util.Log.println(priority, tag ?: "ZCam", "$message\n${android.util.Log.getStackTraceString(t)}")
        }
    }
}
