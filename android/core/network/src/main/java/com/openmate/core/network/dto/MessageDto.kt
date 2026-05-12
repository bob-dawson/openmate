package com.openmate.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MessageWithPartsDto(
    val info: MessageInfoDto,
    val parts: List<PartDto> = emptyList(),
)

@Serializable
data class MessageHeaderDto(
    val info: MessageInfoDto,
)

@Serializable
data class MessageInfoDto(
    val id: String,
    @SerialName("sessionID") val sessionID: String = "",
    val role: String,
    val agent: String = "",
    val time: MessageTimeDto = MessageTimeDto(),
    val model: MessageModelDto? = null,
    @SerialName("parentID") val parentID: String? = null,
    @SerialName("modelID") val modelID: String? = null,
    @SerialName("providerID") val providerID: String? = null,
    val mode: String? = null,
    val path: MessagePathDto? = null,
    val cost: Double? = null,
    val tokens: MessageTokensDto? = null,
    val error: JsonElement? = null,
    val finish: String? = null,
    val variant: String? = null,
)

@Serializable
data class MessageTimeDto(
    val created: Long = 0L,
    val completed: Long? = null,
)

@Serializable
data class MessageModelDto(
    @SerialName("providerID") val providerID: String = "",
    @SerialName("modelID") val modelID: String = "",
    val variant: String? = null,
)

@Serializable
data class MessagePathDto(
    val cwd: String = "",
    val root: String = "",
)

@Serializable
data class MessageTokensDto(
    val total: Long? = null,
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: MessageCacheTokensDto? = null,
)

@Serializable
data class MessageCacheTokensDto(
    val read: Long = 0,
    val write: Long = 0,
)

@Serializable
data class PartDto(
    val type: String,
    val id: String = "",
    @SerialName("sessionID") val sessionID: String = "",
    @SerialName("messageID") val messageID: String = "",
    val text: String? = null,
    val time: JsonElement? = null,
    val metadata: JsonElement? = null,
    val synthetic: Boolean? = null,
    val ignored: Boolean? = null,
    @SerialName("callID") val callID: String? = null,
    val tool: String? = null,
    val state: ToolStateDto? = null,
    val snapshot: String? = null,
    val hash: String? = null,
    val files: List<String>? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null,
    val name: String? = null,
    val reason: String? = null,
    val cost: Double? = null,
    val tokens: JsonElement? = null,
    val prompt: String? = null,
    val description: String? = null,
    val agent: String? = null,
    val auto: Boolean? = null,
    val overflow: Boolean? = null,
    val attempt: Int? = null,
    val error: String? = null,
)

@Serializable
data class ToolStateDto(
    val status: String = "",
    val input: JsonElement? = null,
    val output: String? = null,
    val title: String? = null,
    val metadata: JsonElement? = null,
    val structured: JsonElement? = null,
    val time: JsonElement? = null,
    val error: String? = null,
    val raw: String? = null,
)
