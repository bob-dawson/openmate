package com.openmate.core.network

interface SyncSseConnection {
    val currentBaseUrl: String?

    suspend fun connect(baseUrl: String)

    fun disconnect(traceId: String? = null)
}
