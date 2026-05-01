package com.openmate.core.domain.repository

import com.openmate.core.domain.model.Message
import kotlinx.coroutines.flow.Flow

data class FileAttachment(val path: String, val filename: String, val mime: String)

interface MessageRepository {
    suspend fun syncMessages(sessionID: String, initialLimit: Int = 80)
    suspend fun loadOlderMessages(sessionID: String, cursor: String, limit: Int = 20)
    suspend fun sendMessage(
        sessionID: String,
        content: String,
        providerID: String? = null,
        modelID: String? = null,
        agent: String? = null,
        files: List<FileAttachment> = emptyList(),
    )
    fun observeMessages(sessionID: String): Flow<List<Message>>
}
