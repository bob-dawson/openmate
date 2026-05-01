package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.ToolRef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Entity(tableName = "PermissionEntity")
data class PermissionEntity(
    @PrimaryKey val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: String,
    val metadata: String,
    val always: String,
    val toolMessageID: String? = null,
    val toolCallID: String? = null,
)

private val permJson = Json { ignoreUnknownKeys = true }

fun PermissionEntity.toDomain(): PermissionRequest {
    return PermissionRequest(
        id = id,
        sessionID = sessionID,
        permission = permission,
        patterns = if (patterns.isBlank()) emptyList() else patterns.split("||"),
        metadata = if (metadata.isBlank()) JsonObject(emptyMap()) else permJson.decodeFromString(metadata),
        always = if (always.isBlank()) emptyList() else always.split("||"),
        tool = if (toolMessageID != null) ToolRef(toolMessageID, toolCallID ?: "") else null,
    )
}

fun PermissionRequest.toEntity(): PermissionEntity {
    return PermissionEntity(
        id = id,
        sessionID = sessionID,
        permission = permission,
        patterns = patterns.joinToString("||"),
        metadata = permJson.encodeToString(JsonObject.serializer(), metadata),
        always = always.joinToString("||"),
        toolMessageID = tool?.messageID,
        toolCallID = tool?.callID,
    )
}
