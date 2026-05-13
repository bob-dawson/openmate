package com.openmate.syncdebugger.replayer

import com.openmate.syncdebugger.model.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

object SessionMessageMapper {

    fun dtoToEntity(dto: SyncMessageDto): SessionMessageEntity {
        val roundMark = computeRoundMark(dto.type, dto.data)
        return SessionMessageEntity(
            id = dto.id,
            sessionId = dto.sessionId,
            type = dto.type,
            data = dto.data.toString(),
            timeCreated = dto.timeCreated,
            timeUpdated = dto.timeUpdated,
            completedAt = extractCompletedAt(dto.data),
            roundMark = roundMark,
        )
    }

    fun computeRoundMark(type: String, data: JsonObject): Boolean {
        if (type != "assistant") return true
        val finish = data["finish"]?.jsonPrimitive?.contentOrNull
        val error = data["error"]
        return (finish == "stop" || finish == "length") || error != null
    }

    private fun extractCompletedAt(data: JsonObject): Long? {
        return data["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
    }
}
