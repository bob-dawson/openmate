package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.model.MessageRole
import com.openmate.core.domain.model.Part

@Entity(
    tableName = "MessageEntity",
    indices = [
        Index("sessionID"),
        Index("createdAt"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionID: String,
    val role: String,
    val agent: String? = null,
    val createdAt: Long,
)

fun MessageEntity.toDomain(parts: List<Part>): Message {
    return Message(
        id = id,
        sessionID = sessionID,
        role = MessageRole.valueOf(role),
        agent = agent,
        createdAt = createdAt,
        parts = parts,
    )
}

fun Message.toEntity(): MessageEntity {
    return MessageEntity(
        id = id,
        sessionID = sessionID,
        role = role.name,
        agent = agent,
        createdAt = createdAt,
    )
}
