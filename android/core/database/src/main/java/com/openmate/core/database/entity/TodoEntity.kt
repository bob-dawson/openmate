package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.TodoInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "TodoEntity")
data class TodoEntity(
    @PrimaryKey val sessionID: String,
    val todos: String,
)

private val json = Json { ignoreUnknownKeys = true }

fun TodoEntity.toDomain(): List<TodoInfo> {
    return json.decodeFromString<List<TodoInfo>>(todos)
}

fun List<TodoInfo>.toEntity(sessionID: String): TodoEntity {
    return TodoEntity(
        sessionID = sessionID,
        todos = json.encodeToString(this),
    )
}
