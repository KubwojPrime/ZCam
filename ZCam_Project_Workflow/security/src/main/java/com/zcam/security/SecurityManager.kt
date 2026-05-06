package com.zcam.security

interface SecurityManager {
    suspend fun validateToken(candidate: String): Boolean
    suspend fun validatePin(candidate: String): Boolean
}
