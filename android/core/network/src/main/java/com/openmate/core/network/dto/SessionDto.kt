package com.openmate.core.network.dto

import com.openmate.core.domain.model.Session
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

@Serializable
data class SessionDto(
    val id: String,
    val slug: String = "",
    val title: String = "",
    val directory: String = "",
    @SerialName("projectID") val projectID: String = "",
    @SerialName("workspaceID") val workspaceID: String? = null,
    @SerialName("parentID") val parentID: String? = null,
    val version: String = "",
    val time: SessionTimeDto = SessionTimeDto(),
    val summary: SessionSummaryDto? = null,
    val share: SessionShareDto? = null,
    val revert: SessionRevertDto? = null,
    val permission: JsonElement? = null,
)

@Serializable
data class SessionTimeDto(
    val created: Long = 0L,
    val updated: Long = 0L,
    val compacting: Long? = null,
    val archived: Long? = null,
)

@Serializable
data class SessionSummaryDto(
    val additions: Long = 0L,
    val deletions: Long = 0L,
    val files: Long = 0L,
)

@Serializable
data class SessionShareDto(
    val url: String = "",
)

@Serializable
data class SessionRevertDto(
    @SerialName("messageID") val messageID: String? = null,
    @SerialName("partID") val partID: String? = null,
    val snapshot: String? = null,
    val diff: String? = null,
)

fun SessionDto.toDomain(): Session {
    return Session(
        id = id,
        title = title,
        directory = directory,
        projectID = projectID,
        workspaceID = workspaceID,
        parentID = parentID,
        createdAt = time.created,
        updatedAt = time.updated,
        isCompacting = time.compacting != null,
        isArchived = time.archived != null,
    )
}

internal fun parseEpochMillis(isoString: String): Long {
    if (isoString.isBlank()) return 0L
    return runCatching {
        java.time.OffsetDateTime.parse(isoString).toInstant().toEpochMilli()
    }.getOrDefault(0L)
}
