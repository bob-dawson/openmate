package com.openmate.syncdebugger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class EventsResponseDto(
    val events: List<SyncEventDto> = emptyList(),
    @SerialName("maxSeq") val maxSeq: Long? = null,
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
data class SessionsResponseDto(
    val sessions: List<SyncSessionDto> = emptyList(),
)

@Serializable
data class SyncSessionDto(
    val id: String = "",
    val title: String = "",
    @SerialName("maxSeq") val maxSeq: Long? = null,
)

@Serializable
data class ResolveEvtIdResponseDto(
    @SerialName("evtID") val evtID: String? = null,
)
