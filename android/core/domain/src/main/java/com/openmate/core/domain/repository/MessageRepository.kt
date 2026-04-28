package com.openmate.core.domain.repository

import com.openmate.core.domain.model.Message
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    suspend fun getMessages(sessionID: String, limit: Int, before: String?): List<Message>
    suspend fun sendMessage(sessionID: String, content: String)
    fun observeMessages(sessionID: String): Flow<List<Message>>
}
