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
            message = "发起SSE连接 connecting to /api/bridge/events token=$hasToken trace=$traceId",
        )
    }

    override fun logConnectSuccess(traceId: String, costMs: Long) {
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sse,
            message = "SSE连接成功 connected to sync event stream cost=${costMs}ms trace=$traceId",
        )
    }

    override fun logDisconnected(traceId: String?, currentBaseUrl: String?) {
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sse,
            message = "主动断开SSE disconnect requested currentBaseUrl=$currentBaseUrl${traceId?.let { " trace=$it" }.orEmpty()}",
        )
    }

    override fun logConnectFailure(traceId: String, error: Throwable) {
        logStore.log(
            level = SyncLogLevel.Error,
            category = SyncLogCategory.Sse,
            message = "SSE连接失败 ${error.javaClass.simpleName}: ${error.message} trace=$traceId",
        )
    }

    override fun logStreamClosed(traceId: String) {
        logStore.log(
            level = SyncLogLevel.Warn,
            category = SyncLogCategory.Sse,
            message = "SSE断开 stream closed unexpectedly reconnectIn=3000ms trace=$traceId",
        )
    }

    override fun logNotification(event: BridgeEvent, traceId: String) {
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Sse,
            message = "收到Bridge事件 type=${event.type} messageId=${event.messageId} partId=${event.partId} trace=$traceId",
            sessionId = event.sessionId,
        )
    }
}
