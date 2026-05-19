package com.openmate.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.openmate.core.domain.model.Session
import com.openmate.core.domain.model.SessionRevert
import com.openmate.core.domain.model.SessionStatus
import com.openmate.core.domain.model.SessionTokens

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
    val startedAt: Long? = null,
    val phoneStartedAt: Long? = null,
    val totalDuration: Long? = null,
    val modelProviderID: String? = null,
    val modelID: String? = null,
    val modelName: String? = null,
    val revertMessageID: String? = null,
    val revertPartID: String? = null,
    val revertFrom: String? = null,
    val revertTo: String? = null,
    val cost: Double = 0.0,
    val tokensInput: Long = 0,
    val tokensOutput: Long = 0,
    val tokensReasoning: Long = 0,
    val tokensCacheRead: Long = 0,
    val tokensCacheWrite: Long = 0,
    val agent: String? = null,
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
        startedAt = startedAt,
        phoneStartedAt = phoneStartedAt,
        totalDuration = totalDuration,
        modelProviderID = modelProviderID,
        modelID = modelID,
        modelName = modelName,
        revert = revertMessageID?.let { SessionRevert(it, revertPartID, revertFrom, revertTo) },
        cost = cost,
        tokens = if (tokensInput == 0L && tokensOutput == 0L) null
            else SessionTokens(tokensInput, tokensOutput, tokensReasoning, tokensCacheRead, tokensCacheWrite),
        agent = agent,
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
        startedAt = startedAt,
        phoneStartedAt = phoneStartedAt,
        totalDuration = totalDuration,
        modelProviderID = modelProviderID,
        modelID = modelID,
        modelName = modelName,
        revertMessageID = revert?.messageID,
        revertPartID = revert?.partID,
        revertFrom = revert?.from,
        revertTo = revert?.to,
        cost = cost,
        tokensInput = tokens?.input ?: 0,
        tokensOutput = tokens?.output ?: 0,
        tokensReasoning = tokens?.reasoning ?: 0,
        tokensCacheRead = tokens?.cacheRead ?: 0,
        tokensCacheWrite = tokens?.cacheWrite ?: 0,
        agent = agent,
    )
}
