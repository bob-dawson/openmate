package com.openmate.core.network

import kotlinx.serialization.json.JsonObject

data class SseData(
    val type: String,
    val properties: JsonObject,
)
