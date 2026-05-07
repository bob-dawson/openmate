package com.openmate.core.data.repository

import com.openmate.core.domain.model.Message
import com.openmate.core.domain.repository.FileAttachment
import com.openmate.core.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject

class MessageRepositoryImpl @Inject constructor(
) : MessageRepository {

    override suspend fun syncMessages(sessionID: String, initialLimit: Int): String? = null

    override suspend fun loadOlderMessages(sessionID: String, cursor: String, limit: Int): String? = null

    override suspend fun sendMessage(
        sessionID: String,
        content: String,
        providerID: String?,
        modelID: String?,
        agent: String?,
        files: List<FileAttachment>,
        directory: String?,
    ) {
        // TODO: reimplement using sync API
    }

    override fun observeMessages(sessionID: String): Flow<List<Message>> = flowOf(emptyList())
}
