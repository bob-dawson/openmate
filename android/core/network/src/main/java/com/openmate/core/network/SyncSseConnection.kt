package com.openmate.core.network

interface SyncSseConnection {
    val currentBaseUrl: String?

    suspend fun connect(baseUrl: String, forceRestart: Boolean = false)

    fun disconnect(traceId: String? = null)
}
