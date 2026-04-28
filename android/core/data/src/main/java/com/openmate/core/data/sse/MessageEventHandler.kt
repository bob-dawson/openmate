package com.openmate.core.data.sse

import android.util.Log
import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toEntity
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.network.OpencodeApiClient
import com.openmate.core.network.SseData
import com.openmate.core.network.dto.toDomain
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
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
                    val info = props["info"]?.jsonObject
                    val role = info?.get("role")?.jsonPrimitive?.content
                    val time = info?.get("time")?.jsonObject
                    val completedEl = time?.get("completed")
                    val isCompletedNull = completedEl == null || completedEl is JsonNull
                    val isBusy = role == "assistant" && isCompletedNull
                    val status = if (isBusy) SessionStatus.BUSY.name else SessionStatus.IDLE.name
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
            val dtos = apiClient.getMessages(sessionID, 80, null)
            val db = dbProvider.getActive()
            val domains = dtos.map { it.toDomain() }
            db.messageDao().upsertAll(domains.map { it.toEntity() })

            val allParts = domains.flatMap { msg ->
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
