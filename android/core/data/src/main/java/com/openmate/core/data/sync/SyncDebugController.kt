package com.openmate.core.data.sync

import com.openmate.core.common.AppDispatchers
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SyncSseConnection
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
class SyncDebugController @Inject constructor(
    private val syncSseConnection: SyncSseConnection,
    private val syncSseStarter: SyncSseStarter,
    private val apiClient: OpencodeApiClient,
    private val logStore: SyncLogStore,
    private val sessionMessageRepository: SessionMessageRepository,
    appDispatchers: AppDispatchers,
) {
    private val scope = CoroutineScope(SupervisorJob() + appDispatchers.io)
    val logs: StateFlow<List<SyncLogEntry>> = logStore.entries

    fun clearLogs() {
        logStore.clear()
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Manual,
            title = "日志已清除",
            message = "sync logs cleared by user",
        )
    }

    fun log(
        level: SyncLogLevel,
        category: SyncLogCategory,
        sessionId: String? = null,
        title: String,
        message: String,
    ) {
        logStore.log(
            level = level,
            category = category,
            sessionId = sessionId,
            title = title,
            message = message,
        )
    }

    fun reconnectSse() {
        val traceId = "sse-manual-${System.currentTimeMillis()}"
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Manual,
            title = "用户请求重连SSE",
            message = "reconnect sync sse requested from sync log screen",
            traceId = traceId,
        )
        val baseUrl = syncSseConnection.currentBaseUrl ?: apiClient.baseUrl
        syncSseConnection.disconnect(traceId)
        syncSseStarter.start()
        scope.launch {
            syncSseConnection.connect(baseUrl, forceRestart = true)
        }
    }

    suspend fun triggerManualIncrementalSync(sessionId: String) {
        val traceId = "inc-${System.nanoTime()}"
        logStore.log(
            level = SyncLogLevel.Info,
            category = SyncLogCategory.Manual,
            sessionId = sessionId,
            title = "用户发起增量同步",
            message = "manual incremental sync requested",
            traceId = traceId,
        )
        sessionMessageRepository.incrementalSync(sessionId)
    }
}
