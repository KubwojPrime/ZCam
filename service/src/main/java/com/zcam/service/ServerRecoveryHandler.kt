package com.zcam.service

/** A denied background start needs a user gesture, not another identical retry. */
fun executeServerRecovery(
    startServer: () -> Unit,
    isBackgroundStartRestricted: (Exception) -> Boolean,
    requestUserAction: () -> Unit
): Boolean {
    return try {
        startServer()
        true
    } catch (error: Exception) {
        if (!isBackgroundStartRestricted(error)) throw error
        requestUserAction()
        false
    }
}
