package com.openmate.core.data.sync

import com.openmate.core.network.BridgeEvent
import com.openmate.core.network.SyncSseLogger
import javax.inject.Inject

class SyncSseLoggerImpl @Inject constructor(
    private val logStore: SyncLogStore,
) : SyncSseLogger {
    override fun logConnectStart(traceId: String, hasToken: Boolean) {
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sse,
            title = "发起SSE连接",
            message = "connecting to /api/bridge/events token=$hasToken",
            traceId = traceId,
        )
    }

    override fun logConnectSuccess(traceId: String, costMs: Long) {
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sse,
            title = "SSE连接成功",
            message = "connected to sync event stream cost=${costMs}ms",
            traceId = traceId,
        )
    }

    override fun logDisconnected(traceId: String?, currentBaseUrl: String?) {
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sse,
            title = "主动断开SSE",
            message = "disconnect requested currentBaseUrl=$currentBaseUrl",
            traceId = traceId,
        )
    }

    override fun logConnectFailure(traceId: String, error: Throwable) {
        logStore.log(
            level = SyncLogLevel.Error,
            category = SyncLogCategory.Sse,
            title = "SSE连接失败",
            message = "${error.javaClass.simpleName}: ${error.message}",
            traceId = traceId,
        )
    }

    override fun logStreamClosed(traceId: String) {
        logStore.log(
            level = SyncLogLevel.Warn,
            category = SyncLogCategory.Sse,
            title = "SSE断开",
            message = "stream closed unexpectedly reconnectIn=3000ms",
            traceId = traceId,
        )
    }

    override fun logNotification(event: BridgeEvent, traceId: String) {
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sse,
            sessionId = event.sessionId,
            title = "收到Bridge事件",
            message = "type=${event.type} messageId=${event.messageId} partId=${event.partId}",
            traceId = traceId,
        )
    }
}
