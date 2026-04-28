package com.openmate.core.data.repository

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toDomain
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.repository.MessageRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class MessageRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) : MessageRepository {

    override suspend fun getMessages(sessionID: String, limit: Int, before: String?): List<Message> {
        val dtos = api.getMessages(sessionID, limit, before)
        val db = dbProvider.getActive()
        val msgEntities = dtos.map { it.toDomain().toEntity() }
        db.messageDao().upsertAll(msgEntities)

        val allParts = dtos.flatMap { msgDto ->
            val msgDomain = msgDto.toDomain()
            msgDomain.parts.mapIndexed { idx, part ->
                part.toEntity(msgDomain.id, sessionID, idx)
            }
        }
        if (allParts.isNotEmpty()) {
            db.partDao().upsertAll(allParts)
        }

        return db.messageDao().getBySession(sessionID, limit).map { msgEntity ->
            val parts = db.partDao().getByMessage(msgEntity.id).map { it.toDomain() }
            msgEntity.toDomain(parts)
        }
    }

    override suspend fun sendMessage(sessionID: String, content: String) {
        api.sendPrompt(sessionID, content)
    }

    override fun observeMessages(sessionID: String): Flow<List<Message>> {
        val db = dbProvider.getActive()
        return db.messageDao().observeBySession(sessionID).combine(db.partDao().observeBySession(sessionID)) { msgEntities, allParts ->
            val partsByMsg = allParts.groupBy { it.messageID }
            msgEntities.map { msgEntity ->
                val parts = (partsByMsg[msgEntity.id] ?: emptyList()).map { it.toDomain() }
                msgEntity.toDomain(parts)
            }
        }
    }
}
