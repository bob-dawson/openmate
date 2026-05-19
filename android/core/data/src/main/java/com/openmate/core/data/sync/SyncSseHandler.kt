package com.openmate.core.data.sync

import android.util.Log
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncSseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
    private val activeSyncs = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var activeSessionId: String? = null

    override fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
    }

    override fun start() {
        if (collectJob?.isActive == true) return
        Log.d("SyncSseHandler", "start: subscribing to notifications")

        collectJob = syncSseClient.notifications
            .onEach { notification ->
                val notifyTrace = "notify-${notification.sessionId}-${notification.seq}"
                Log.d("SyncSseHandler", "received: session=${notification.sessionId} seq=${notification.seq}")
                if (notification.sessionId != activeSessionId) {
                    Log.d("SyncSseHandler", "skip: not active session (active=$activeSessionId)")
                    return@onEach
                }
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sse,
                    sessionId = notification.sessionId,
                    title = "同步通知",
                    message = "seq=${notification.seq}",
                    relatedSeq = notification.seq,
                    traceId = notifyTrace,
                )
                performSync(notification.sessionId)
            }
            .launchIn(scope)
    }

    private suspend fun performSync(sessionId: String) {
        val syncTrace = "sync-${sessionId}-${System.currentTimeMillis()}"
        if (activeSyncs.putIfAbsent(sessionId, true) != null) {
            Log.d("SyncSseHandler", "skip: sync already active for $sessionId")
            return
        }
        try {
            val t0 = System.currentTimeMillis()
            logStore.log(
                level = SyncLogLevel.Info,
                category = SyncLogCategory.Sync,
                sessionId = sessionId,
                title = "发起增量同步",
                message = "trigger=sse",
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
