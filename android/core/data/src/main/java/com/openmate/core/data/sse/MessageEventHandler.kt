package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SseData
import com.openmate.core.network.dto.toDomain
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

open class MessageEventHandler @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
    private val apiClient: OpencodeApiClient,
) {
    suspend fun handle(type: String, event: SseData) {
        val props = event.properties
        val sessionID = props["sessionID"]?.jsonPrimitive?.content ?: return

        try {
            val db = dbProvider.getActive()

            when (type) {
                "message.updated" -> {
                    val role = props["role"]?.jsonPrimitive?.content
                    val completed = props["completed"]?.jsonPrimitive?.content

                    val status = if (role == "assistant" && (completed == null || completed == "null")) {
                        SessionStatus.BUSY.name
                    } else {
                        SessionStatus.IDLE.name
                    }
                    val existing = db.sessionDao().getById(sessionID)
                    if (existing != null) {
                        db.sessionDao().upsert(existing.copy(status = status))
                    }

                    refreshMessages(sessionID)
                }
                "message.part.updated" -> {
                    refreshMessages(sessionID)
                }
                "message.part.delta" -> {
                    val existing = db.sessionDao().getById(sessionID)
                    if (existing != null && existing.status != SessionStatus.BUSY.name) {
                        db.sessionDao().upsert(existing.copy(status = SessionStatus.BUSY.name))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("MessageEventHandler", "$type failed", e)
        }
    }

    private suspend fun refreshMessages(sessionID: String) {
        try {
            val dtos = apiClient.getMessages(sessionID, 80, null)
            val db = dbProvider.getActive()
            val msgEntities = dtos.map { dto ->
                val msg = dto.toDomain()
                msg.toEntity()
            }
            db.messageDao().upsertAll(msgEntities)

            val allParts = dtos.flatMap { dto ->
                val msg = dto.toDomain()
                msg.parts.mapIndexed { idx, part ->
                    part.toEntity(msg.id, sessionID, idx)
                }
            }
            if (allParts.isNotEmpty()) {
                db.partDao().upsertAll(allParts)
            }
        } catch (e: Exception) {
            Log.w("MessageEventHandler", "refreshMessages failed", e)
        }
    }
}
