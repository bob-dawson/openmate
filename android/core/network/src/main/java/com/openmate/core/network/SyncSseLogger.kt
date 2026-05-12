package com.openmate.core.network

interface SyncSseLogger {
    fun logConnectStart(traceId: String, hasToken: Boolean)
    fun logConnectSuccess(traceId: String, costMs: Long)
    fun logDisconnected(traceId: String?, currentBaseUrl: String?)
    fun logConnectFailure(traceId: String, error: Throwable)
    fun logStreamClosed(traceId: String)
    fun logNotification(sessionId: String, seq: Long, traceId: String)
}
