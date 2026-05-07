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
import kotlinx.coroutines.launch
import javax.inject.Inject

class SyncSseHandler @Inject constructor(
    private val syncSseClient: SyncSseClient,
    private val repository: SessionMessageRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var collectJob: Job? = null

    fun start() {
        if (collectJob?.isActive == true) return
        Log.d("SyncSseHandler", "start: subscribing to notifications")
        collectJob = syncSseClient.notifications
            .onEach { notification ->
                Log.d("SyncSseHandler", "received: session=${notification.sessionId} seq=${notification.seq}")
                scope.launch {
                    try {
                        val t0 = System.currentTimeMillis()
                        val lastSeq = repository.getLastSeq(notification.sessionId)
                        if (lastSeq != null && lastSeq > 0 && notification.seq > lastSeq) {
                            repository.incrementalSync(notification.sessionId)
                            Log.d("SyncSseHandler", "sync done: ${System.currentTimeMillis() - t0}ms")
                        } else {
                            Log.d("SyncSseHandler", "skip: lastSeq=$lastSeq notification.seq=${notification.seq}")
                        }
                    } catch (e: Exception) {
                        Log.w("SyncSseHandler", "sync failed: ${e.message}", e)
                    }
                }
            }
            .launchIn(scope)
    }
}
