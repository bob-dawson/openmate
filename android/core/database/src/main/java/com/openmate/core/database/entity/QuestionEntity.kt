package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "QuestionEntity")
data class QuestionEntity(
    @PrimaryKey val id: String,
    val sessionID: String,
    val questions: String,
)

private val json = Json { ignoreUnknownKeys = true }

fun QuestionEntity.toDomain(): QuestionRequest {
    val items = json.decodeFromString<List<QuestionInfo>>(questions)
    return QuestionRequest(
        id = id,
        sessionID = sessionID,
        questions = items,
    )
}

fun QuestionRequest.toEntity(): QuestionEntity {
    return QuestionEntity(
        id = id,
        sessionID = sessionID,
        questions = json.encodeToString(questions),
    )
}
