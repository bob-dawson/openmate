package com.openmate.core.data.sse

import com.openmate.core.domain.model.SessionRetryStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

@Singleton
class SessionRetryStateStore @Inject constructor() {
    private val retryStatuses = MutableStateFlow<Map<String, SessionRetryStatus>>(emptyMap())

    fun observe(sessionId: String): Flow<SessionRetryStatus?> {
        return retryStatuses.map { it[sessionId] }
    }

    fun update(sessionId: String, status: SessionRetryStatus?) {
        retryStatuses.value = retryStatuses.value.toMutableMap().apply {
            if (status == null) remove(sessionId) else put(sessionId, status)
        }
    }
}
