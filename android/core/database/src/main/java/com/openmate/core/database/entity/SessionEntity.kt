package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionStatus

@Entity(
    tableName = "SessionEntity",
    indices = [
        Index("directory"),
        Index("updatedAt"),
    ],
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val directory: String,
    val projectID: String,
    val workspaceID: String? = null,
    val parentID: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val isCompacting: Boolean = false,
    val isArchived: Boolean = false,
    val status: String? = null,
    val syncAnchor: String? = null,
)

fun SessionEntity.toDomain(): Session {
    return Session(
        id = id,
        title = title,
        directory = directory,
        projectID = projectID,
        workspaceID = workspaceID,
        parentID = parentID,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isCompacting = isCompacting,
        isArchived = isArchived,
        status = status?.let { runCatching { SessionStatus.valueOf(it) }.getOrNull() },
    )
}

fun Session.toEntity(): SessionEntity {
    return SessionEntity(
        id = id,
        title = title,
        directory = directory,
        projectID = projectID,
        workspaceID = workspaceID,
        parentID = parentID,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isCompacting = isCompacting,
        isArchived = isArchived,
        status = status?.name,
        syncAnchor = null,
    )
}
