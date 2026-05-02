package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.Part
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
        val sessionID = props["sessionID"]?.jsonPrimitive?.content
        Log.d("MessageEventHandler", "handle: type=$type sessionID=$sessionID")
        if (sessionID == null) {
            Log.w("MessageEventHandler", "handle: no sessionID in props, keys=${props.keys}")
            return
        }

        try {
            val db = dbProvider.getActive()

            when (type) {
                "message.updated" -> {
                    Log.d("MessageEventHandler", "refreshMessages for sessionID=$sessionID")
                    refreshMessages(sessionID)
                }
                "message.part.updated" -> {
                    refreshMessages(sessionID)
                }
                "message.part.delta" -> {
                    val partID = props["partID"]?.jsonPrimitive?.content
                    val delta = props["delta"]?.jsonPrimitive?.content
                    if (partID != null && delta != null) {
                        val part = db.partDao().getById(partID)
                        if (part != null) {
                            db.partDao().upsert(part.copy(text = (part.text ?: "") + delta))
                        }
                    }
                }
                "message.removed" -> {
                    val messageID = props["messageID"]?.jsonPrimitive?.content ?: return
                    db.messageDao().delete(messageID)
                    db.partDao().deleteByMessage(messageID)
                }
                "message.part.removed" -> {
                    val partID = props["partID"]?.jsonPrimitive?.content ?: return
                    db.partDao().delete(partID)
                }
            }
        } catch (e: Exception) {
            Log.w("MessageEventHandler", "$type failed", e)
        }
    }

    private suspend fun refreshMessages(sessionID: String) {
        try {
            val page = apiClient.getMessages(sessionID, 80, null)
            val db = dbProvider.getActive()
            val domains = page.items.map { it.toDomain() }
            Log.d("MessageEventHandler", "refreshMessages: got ${domains.size} messages for $sessionID")
            db.messageDao().upsertAll(domains.map { it.toEntity() })

            val allParts = domains.flatMap { msg ->
                msg.parts.filter { it !is Part.TextPart || !it.synthetic }.mapIndexed { idx, part ->
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
