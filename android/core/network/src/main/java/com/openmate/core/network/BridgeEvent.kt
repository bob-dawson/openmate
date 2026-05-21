package com.openmate.core.network

import kotlinx.serialization.json.JsonObject

data class BridgeEvent(
    val type: String,
    val properties: JsonObject,
    val sessionId: String?,
    val directory: String?,
    val messageId: String?,
    val partId: String?,
    val rawJson: String,
)
