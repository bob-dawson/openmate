package com.openmate.core.data.sync

import android.util.Log
import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncSseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

class SyncSseHandler @Inject constructor(
    private val syncSseClient: SyncSseClient,
    private val repository: SessionMessageRepository,
    private val logStore: SyncLogStore,
) : SyncSseStarter, SyncRecoveryTrigger {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private val activeSyncs = ConcurrentHashMap<String, Boolean>()

    @Volatile
    private var activeSessionId: String? = null

    override fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        activeSyncs.clear()
        Log.d("SyncSseHandler", "stop: cancelled collectJob, cleared activeSyncs (activeSessionId=$activeSessionId)")
    }

    override fun start() {
        if (collectJob?.isActive == true) return
        Log.d("SyncSseHandler", "start: subscribing to notifications")

        collectJob = syncSseClient.notifications
            .onEach { event ->
                val sessionId = event.sessionId ?: run {
                    Log.d("SyncSseHandler", "skip: event without sessionId type=${event.type}")
                    return@onEach
                }
                val notifyTrace = "notify-${event.type}-$sessionId"
                Log.d("SyncSseHandler", "received: type=${event.type} session=$sessionId")
                if (sessionId != activeSessionId) {
                    Log.d("SyncSseHandler", "skip: not active session (active=$activeSessionId)")
                    return@onEach
                }
                logStore.log(
                    level = SyncLogLevel.Info,
                    category = SyncLogCategory.Sse,
                    message = "Bridge事件触发同步 type=${event.type} messageId=${event.messageId} partId=${event.partId} trace=$notifyTrace",
                    sessionId = sessionId,
                )
                performSync(sessionId)
            }
            .launchIn(scope)
    }

    override fun requestCatchUpSync(sessionId: String?) {
        val targetSessionId = sessionId ?: activeSessionId ?: return
        scope.launch {
            performSync(targetSessionId)
        }
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
                message = "发起增量同步 trigger=sse trace=$syncTrace",
                sessionId = sessionId,
            )
            repository.incrementalSyncAndNotify(sessionId)
            Log.d("SyncSseHandler", "sync done: ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            logStore.log(
                level = SyncLogLevel.Error,
                category = SyncLogCategory.Sync,
                message = "自动增量同步失败 ${e.javaClass.simpleName}: ${e.message} trace=$syncTrace",
                sessionId = sessionId,
            )
            Log.w("SyncSseHandler", "sync failed: ${e.message}", e)
        } finally {
            activeSyncs.remove(sessionId)
        }
    }
}
