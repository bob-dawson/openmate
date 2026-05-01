package com.openmate.core.data.repository

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toDomain
import com.openmate.core.database.entity.toDomainWithMetadata
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.model.MessageRole
import com.openmate.core.domain.repository.MessageRepository
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.dto.MessageWithPartsDto
import com.openmate.core.network.dto.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject

class MessageRepositoryImpl @Inject constructor(
    private val api: OpencodeApiClient,
    private val dbProvider: ActiveDatabaseProvider,
) : MessageRepository {

    companion object {
        private const val TAG = "MessageRepo"
        private const val SYNC_LIMIT_INITIAL = 5
        private const val SYNC_LIMIT_MAX = 80
        private val metadataJson = Json { ignoreUnknownKeys = true }
    }

    override suspend fun syncMessages(sessionID: String, initialLimit: Int) {
        val db = dbProvider.getActive()
        val anchor = db.sessionDao().getSyncAnchor(sessionID)
        var limit = if (anchor != null) SYNC_LIMIT_INITIAL else initialLimit
        var cursor: String? = null
        var syncedAnchor = false

        while (!syncedAnchor) {
            val page = api.getMessages(sessionID, limit, cursor)
            if (page.items.isEmpty()) break

            upsertPage(sessionID, page.items)

            if (anchor != null) {
                val anchorFound = page.items.any { it.info.id == anchor }
                if (anchorFound) {
                    syncedAnchor = true
                } else if (page.nextCursor != null) {
                    cursor = page.nextCursor
                    limit = minOf(limit * 2, SYNC_LIMIT_MAX)
                } else {
                    syncedAnchor = true
                }
            } else {
                syncedAnchor = true
            }
        }

        val newAnchor = findNewAnchor(sessionID)
        if (newAnchor != null) {
            db.sessionDao().updateSyncAnchor(sessionID, newAnchor)
        }
    }

    override suspend fun loadOlderMessages(sessionID: String, cursor: String, limit: Int) {
        val page = api.getMessages(sessionID, limit, cursor)
        if (page.items.isEmpty()) return
        upsertPage(sessionID, page.items)
    }

    private suspend fun upsertPage(sessionID: String, items: List<MessageWithPartsDto>) {
        val db = dbProvider.getActive()
        val msgEntities = items.map { it.toDomain().toEntity() }
        db.messageDao().upsertAll(msgEntities)

        val allParts = items.flatMap { msgDto ->
            val msgDomain = msgDto.toDomain()
            msgDomain.parts.mapIndexed { idx, part ->
                part.toEntity(msgDomain.id, sessionID, idx)
            }
        }
        if (allParts.isNotEmpty()) {
            db.partDao().upsertAll(allParts)
        }
    }

    private suspend fun findNewAnchor(sessionID: String): String? {
        val db = dbProvider.getActive()
        val msgs = db.messageDao().getBySession(sessionID, SYNC_LIMIT_MAX)
            .sortedByDescending { it.createdAt }
        return msgs.firstOrNull { it.role == MessageRole.ASSISTANT.name && it.completedAt != null }?.id
    }

    override suspend fun sendMessage(sessionID: String, content: String) {
        api.sendPrompt(sessionID, content)
    }

    override fun observeMessages(sessionID: String): Flow<List<Message>> {
        val db = dbProvider.getActive()
        return db.messageDao().observeBySession(sessionID).combine(
            db.partDao().observeBySessionLite(sessionID)
        ) { msgEntities, liteParts ->
            val taskIdsNeedingMetadata = liteParts
                .filter { it.type == "tool" && it.toolName == "task" && it.toolResult == null }
                .map { it.id }

            val metadataMap = if (taskIdsNeedingMetadata.isNotEmpty()) {
                db.partDao().getMetadataByIds(taskIdsNeedingMetadata)
                    .mapNotNull { tuple ->
                        tuple.toolMetadata?.let { raw ->
                            runCatching { metadataJson.decodeFromString(JsonObject.serializer(), raw) }.getOrNull()
                        }?.let { tuple.id to it }
                    }.toMap()
            } else emptyMap()

            val partsByMsg = liteParts.groupBy { it.messageID }
            msgEntities.map { msgEntity ->
                val parts = (partsByMsg[msgEntity.id] ?: emptyList()).map { lite ->
                    lite.toDomainWithMetadata(metadataMap[lite.id])
                }
                msgEntity.toDomain(parts)
            }
        }
    }
}
