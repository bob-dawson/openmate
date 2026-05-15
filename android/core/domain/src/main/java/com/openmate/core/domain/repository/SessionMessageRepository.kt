package com.openmate.core.domain.repository

import com.openmate.core.domain.model.SessionMessage
import com.openmate.core.domain.model.SessionMessageSyncEvent
import com.openmate.core.domain.model.SessionMessageSyncResult
import kotlinx.coroutines.flow.Flow

interface SessionMessageRepository {
    fun observeMessages(sessionId: String): Flow<List<SessionMessage>>
    fun observeSyncEvents(): Flow<SessionMessageSyncEvent>
    suspend fun getRecentWindow(sessionId: String, limit: Int): List<SessionMessage>
    suspend fun getOlderPage(sessionId: String, beforeTimeCreated: Long, beforeId: String, limit: Int): List<SessionMessage>
    suspend fun getOlderPageByUserTurns(sessionId: String, beforeTimeCreated: Long, beforeId: String, userTurns: Int): List<SessionMessage>
    suspend fun findBusyStartTime(sessionId: String): Long?
    suspend fun initSync(sessionId: String, limit: Int = 30): SessionMessageSyncResult
    suspend fun incrementalSync(sessionId: String): SessionMessageSyncResult
    suspend fun incrementalSyncAndNotify(sessionId: String): SessionMessageSyncResult
    suspend fun fetchFullMessage(sessionId: String, messageId: String)
    suspend fun getLastSeq(sessionId: String): Long?
    suspend fun deleteMessage(sessionId: String, messageId: String)
}
