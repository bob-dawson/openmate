package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.domain.repository.MessageRepository
import com.openmate.core.network.SseData
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

open class MessageEventHandler @Inject constructor(
    private val dbProvider: ActiveDatabaseProvider,
    private val messageRepository: MessageRepository,
) {
    suspend fun handle(type: String, event: SseData) {
        val props = event.properties
        val sessionID = props["sessionID"]?.jsonPrimitive?.content
        if (sessionID == null) return

        try {
            when (type) {
                "message.updated", "message.part.updated" -> {
                    messageRepository.syncMessages(sessionID, 10)
                }
                "message.part.delta" -> {
                    val partID = props["partID"]?.jsonPrimitive?.content
                    val delta = props["delta"]?.jsonPrimitive?.content
                    if (partID != null && delta != null) {
                        val db = dbProvider.getActive()
                        val part = db.partDao().getById(partID)
                        if (part != null) {
                            db.partDao().upsert(part.copy(text = (part.text ?: "") + delta))
                        }
                    }
                }
                "message.removed" -> {
                    val messageID = props["messageID"]?.jsonPrimitive?.content ?: return
                    val db = dbProvider.getActive()
                    db.messageDao().delete(messageID)
                    db.partDao().deleteByMessage(messageID)
                }
                "message.part.removed" -> {
                    val partID = props["partID"]?.jsonPrimitive?.content ?: return
                    dbProvider.getActive().partDao().delete(partID)
                }
            }
        } catch (e: Exception) {
            Log.w("MessageEventHandler", "$type failed", e)
        }
    }
}
