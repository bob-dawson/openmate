package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_message_full_content")
data class SessionMessageFullContentEntity(
    @PrimaryKey val messageId: String,
    val content: String,
    val fetchedAt: Long,
)
