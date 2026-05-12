package com.openmate.core.data.sync

import android.util.Log
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncSseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class SyncSseHandler @Inject constructor(
    private val syncSseClient: SyncSseClient,
    private val repository: SessionMessageRepository,
    private val logStore: SyncLogStore,
) : SyncSseStarter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private var debounceJob: Job? = null
    private val pendingNotifications = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val activeSyncs = ConcurrentHashMap<String, Boolean>()

    override fun start() {
        if (collectJob?.isActive == true) return
        Log.d("SyncSseHandler", "start: subscribing to notifications")

        debounceJob = pendingNotifications
            .debounce(500L)
            .onEach { sessionId ->
                performSync(sessionId)
            }
            .launchIn(scope)

        collectJob = syncSseClient.notifications
            .onEach { notification ->
                val notifyTrace = "notify-${notification.sessionId}-${notification.seq}"
                Log.d("SyncSseHandler", "received: session=${notification.sessionId} seq=${notification.seq}")
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sse,
                    sessionId = notification.sessionId,
                    title = "同步通知入队",
                    message = "queued for debounce window=500ms",
                    relatedSeq = notification.seq,
                    traceId = notifyTrace,
                )
                pendingNotifications.tryEmit(notification.sessionId)
            }
            .launchIn(scope)
    }

    private suspend fun performSync(sessionId: String) {
        val syncTrace = "sync-${sessionId}-${System.currentTimeMillis()}"
        if (activeSyncs.putIfAbsent(sessionId, true) != null) {
            logStore.log(
                level = SyncLogLevel.Warn,
                category = SyncLogCategory.Sync,
                sessionId = sessionId,
                title = "跳过增量同步",
                message = "sync already active for session",
                traceId = syncTrace,
            )
            Log.d("SyncSseHandler", "debounce skip: sync already active for $sessionId")
            return
        }
        try {
            val t0 = System.currentTimeMillis()
            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sync,
                sessionId = sessionId,
                title = "准备发起增量同步",
                message = "debounce elapsed starting incremental sync trigger=sse",
                traceId = syncTrace,
            )
            repository.incrementalSyncAndNotify(sessionId)
            Log.d("SyncSseHandler", "sync done: ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            logStore.log(
                level = SyncLogLevel.Error,
                category = SyncLogCategory.Sync,
                sessionId = sessionId,
                title = "自动增量同步失败",
                message = "${e.javaClass.simpleName}: ${e.message}",
                traceId = syncTrace,
            )
            Log.w("SyncSseHandler", "sync failed: ${e.message}", e)
        } finally {
            activeSyncs.remove(sessionId)
        }
    }
}
