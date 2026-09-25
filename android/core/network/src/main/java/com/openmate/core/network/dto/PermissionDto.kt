package com.openmate.core.network.dto

import com.openmate.core.domain.model.PermissionReply
import com.openmate.core.domain.model.PermissionRequest
import com.openmate.core.domain.model.ToolRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class PermissionDto(
    val id: String,
    @SerialName("sessionID") val sessionID: String = "",
    val action: String = "",
    val resources: List<String> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
    val save: List<String> = emptyList(),
    val source: PermissionSourceDto? = null,
)

@Serializable
data class PermissionSourceDto(
    val type: String = "",
    val id: String = "",
    @SerialName("messageID") val messageID: String = "",
    @SerialName("callID") val callID: String = "",
)

fun PermissionDto.toDomain(): PermissionRequest {
    return PermissionRequest(
        id = id,
        sessionID = sessionID,
        permission = action,
        patterns = resources,
        metadata = metadata,
        always = save,
        tool = source?.let { ToolRef(it.messageID, it.callID.ifEmpty { it.id }) },
    )
}
