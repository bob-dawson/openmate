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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null
    private var debounceJob: Job? = null
    private val pendingNotifications = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val activeSyncs = ConcurrentHashMap<String, Boolean>()

    fun start() {
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
                Log.d("SyncSseHandler", "received: session=${notification.sessionId} seq=${notification.seq}")
                pendingNotifications.tryEmit(notification.sessionId)
            }
            .launchIn(scope)
    }

    private suspend fun performSync(sessionId: String) {
        if (activeSyncs.putIfAbsent(sessionId, true) != null) {
            Log.d("SyncSseHandler", "debounce skip: sync already active for $sessionId")
            return
        }
        try {
            val t0 = System.currentTimeMillis()
            repository.incrementalSync(sessionId)
            Log.d("SyncSseHandler", "sync done: ${System.currentTimeMillis() - t0}ms")
        } catch (e: Exception) {
            Log.w("SyncSseHandler", "sync failed: ${e.message}", e)
        } finally {
            activeSyncs.remove(sessionId)
        }
    }
}
