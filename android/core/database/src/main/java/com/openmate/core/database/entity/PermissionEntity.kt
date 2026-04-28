package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.ToolRef

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

fun PermissionEntity.toDomain(): PermissionRequest {
    return PermissionRequest(
        id = id,
        sessionID = sessionID,
        permission = permission,
        patterns = if (patterns.isBlank()) emptyList() else patterns.split("||"),
        metadata = emptyMap(),
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
        metadata = metadata.entries.joinToString("||") { "${it.key}=${it.value}" },
        always = always.joinToString("||"),
        toolMessageID = tool?.messageID,
        toolCallID = tool?.callID,
    )
}
