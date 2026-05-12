package com.openmate.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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


