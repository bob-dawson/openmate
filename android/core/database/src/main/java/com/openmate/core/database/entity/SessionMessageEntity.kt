package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_message",
    indices = [
        Index("sessionId"),
        Index("sessionId", "type"),
        Index("timeCreated"),
    ],
)
data class SessionMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val type: String,
    val data: String,
    val timeCreated: Long,
    val timeUpdated: Long,
    val completedAt: Long? = null,
)
