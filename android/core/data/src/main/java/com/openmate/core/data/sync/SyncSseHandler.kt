package com.openmate.core.data.sync

import com.openmate.core.domain.repository.SessionMessageRepository
import com.openmate.core.network.SyncSseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

class SyncSseHandler @Inject constructor(
    private val syncSseClient: SyncSseClient,
    private val repository: SessionMessageRepository,
) {
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        syncSseClient.notifications
            .onEach { notification ->
                val lastSeq = repository.getLastSeq(notification.sessionId)
                if (lastSeq != null && notification.seq > lastSeq) {
                    scope.launch {
                        repository.incrementalSync(notification.sessionId)
                    }
                }
            }
            .launchIn(scope)
    }
}
