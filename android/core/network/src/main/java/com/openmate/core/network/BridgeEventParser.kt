package com.openmate.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object BridgeEventParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): BridgeEvent? {
        val root = runCatching {
            json.parseToJsonElement(body) as? JsonObject
        }.getOrNull() ?: return null

        val type = root.requiredString("type") ?: return null
        val properties = root["properties"] as? JsonObject ?: return null
        val sessionId = properties.parseOptionalString("sessionID")
        val directory = properties.parseOptionalString("directory")
        val messageId = properties.parseOptionalString("messageID")
        val partId = properties.parseOptionalString("partID")

        if (sessionId.malformed || directory.malformed || messageId.malformed || partId.malformed) {
            return null
        }

        return BridgeEvent(
            type = type,
            properties = properties,
            sessionId = sessionId.value,
            directory = directory.value,
            messageId = messageId.value,
            partId = partId.value,
            rawJson = body,
        )
    }

    private fun JsonObject.requiredString(key: String): String? {
        return (this[key] as? JsonPrimitive)?.contentOrNull
    }

    private fun JsonObject.parseOptionalString(key: String): ParsedOptionalString {
        return when (val value = this[key]) {
            null -> ParsedOptionalString(value = null, malformed = false)
            is JsonPrimitive -> ParsedOptionalString(value = value.contentOrNull, malformed = false)
            else -> ParsedOptionalString(value = null, malformed = true)
        }
    }

    private data class ParsedOptionalString(val value: String?, val malformed: Boolean)
}
