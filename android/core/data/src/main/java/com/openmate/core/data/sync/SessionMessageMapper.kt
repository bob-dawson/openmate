package com.openmate.core.data.sync

import com.openmate.core.database.entity.SessionMessageEntity
import com.openmate.core.network.dto.SyncMessageDto

object SessionMessageMapper {

    fun dtoToEntity(dto: SyncMessageDto): SessionMessageEntity {
        return SessionMessageEntity(
            id = dto.id,
            sessionId = dto.sessionId,
            type = dto.type,
            data = dto.data.toString(),
            timeCreated = dto.timeCreated,
            timeUpdated = dto.timeUpdated,
        )
    }
}
