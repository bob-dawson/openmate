package com.openmate.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class InitResponseDto(
    val messages: List<SyncMessageDto> = emptyList(),
    @SerialName("maxSeq") val maxSeq: Long? = null,
)

@Serializable
data class SyncMessageDto(
    val id: String = "",
    @SerialName("sessionId") val sessionId: String = "",
    val type: String = "",
    @SerialName("timeCreated") val timeCreated: Long = 0,
    @SerialName("timeUpdated") val timeUpdated: Long = 0,
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class EventsResponseDto(
    val events: List<SyncEventDto> = emptyList(),
    @SerialName("maxSeq") val maxSeq: Long? = null,
)

data class EventsPayloadDto(
    val response: EventsResponseDto,
    val rawBody: String,
    val rawEventBodies: List<String>,
)

@Serializable
data class SyncEventDto(
    val id: String = "",
    @SerialName("aggregateId") val aggregateId: String = "",
    val seq: Long = 0,
    val type: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class FullMessageResponseDto(
    val id: String = "",
    val type: String = "",
    val data: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SessionsResponseDto(
    val sessions: List<SyncSessionDto> = emptyList(),
)

@Serializable
data class SyncSessionDto(
    val id: String = "",
    val title: String = "",
    val agent: String? = null,
    val model: JsonObject? = null,
    @SerialName("timeCreated") val timeCreated: Long = 0,
    @SerialName("timeUpdated") val timeUpdated: Long = 0,
    @SerialName("hasEvents") val hasEvents: Boolean = false,
    @SerialName("maxSeq") val maxSeq: Long? = null,
)
