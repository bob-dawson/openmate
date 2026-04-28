package com.openmate.core.network.dto

import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.ToolRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PermissionDto(
    val id: String,
    @SerialName("sessionID") val sessionID: String = "",
    val permission: String = "",
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val always: List<String> = emptyList(),
    val tool: PermissionToolDto? = null,
)

@Serializable
data class PermissionToolDto(
    @SerialName("messageID") val messageID: String = "",
    @SerialName("callID") val callID: String = "",
)

fun PermissionDto.toDomain(): PermissionRequest {
    return PermissionRequest(
        id = id,
        sessionID = sessionID,
        permission = permission,
        patterns = patterns,
        metadata = metadata,
        always = always,
        tool = tool?.let { ToolRef(it.messageID, it.callID) },
    )
}
