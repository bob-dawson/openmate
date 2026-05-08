package com.openmate.core.data.sync

import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.network.dto.SyncMessageDto
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object SessionMessageMapper {

    fun dtoToEntity(dto: SyncMessageDto): SessionMessageEntity {
        return SessionMessageEntity(
            id = dto.id,
            sessionId = dto.sessionId,
            type = dto.type,
            data = dto.data.toString(),
            timeCreated = dto.timeCreated,
            timeUpdated = dto.timeUpdated,
            completedAt = extractCompletedAt(dto.data),
        )
    }

    private fun extractCompletedAt(data: kotlinx.serialization.json.JsonObject): Long? {
        return data["time"]?.jsonObject?.get("completed")?.jsonPrimitive?.longOrNull
    }
}
