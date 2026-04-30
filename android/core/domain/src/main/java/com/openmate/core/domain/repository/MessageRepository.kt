package com.openmate.core.domain.repository

import com.openmate.core.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun syncMessages(sessionID: String, initialLimit: Int = 80)
    suspend fun loadOlderMessages(sessionID: String, cursor: String, limit: Int = 20)
    suspend fun sendMessage(sessionID: String, content: String)
    fun observeMessages(sessionID: String): Flow<List<Message>>
}
