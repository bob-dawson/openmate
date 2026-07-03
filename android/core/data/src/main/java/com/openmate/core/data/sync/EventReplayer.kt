package com.openmate.core.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class ReplayEvent(
    val id: String,
    val type: String,
    val data: JsonObject,
)

sealed class ReplayChange {
    data class Delete(val id: String) : ReplayChange()
}

class EventReplayer {

    suspend fun processEvent(
        event: ReplayEvent,
        sessionId: String,
    ): List<ReplayChange> {
        when (event.type) {
            "message.removed" -> {
                val messageId = event.data["messageID"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
                return listOf(ReplayChange.Delete(messageId))
            }
        }
        return emptyList()
    }
}
