package com.openmate.core.network.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One undelivered item from opencode's `session_inbox`.
 *
 * `time.created` is when the item was enqueued, not when the agent receives it.
 * Files may be `data:` payloads or `media:` refs; this DTO only needs their count and mime.
 */
@Serializable
data class SessionInboxItemDto(
    val id: String,
    val type: String,
    val delivery: String = "steer",
    val time: SessionInboxTimeDto? = null,
    val payload: JsonObject? = null,
)

@Serializable
data class SessionInboxTimeDto(
    val created: Long = 0,
)
