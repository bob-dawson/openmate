package com.openmate.core.domain.repository

import com.openmate.core.domain.model.SessionMessage
import kotlinx.coroutines.flow.Flow

interface SessionMessageRepository {
    fun observeMessages(sessionId: String): Flow<List<SessionMessage>>
    suspend fun initSync(sessionId: String, limit: Int = 30)
    suspend fun incrementalSync(sessionId: String)
    suspend fun fetchFullMessage(sessionId: String, messageId: String)
    suspend fun getLastSeq(sessionId: String): Long?
}
