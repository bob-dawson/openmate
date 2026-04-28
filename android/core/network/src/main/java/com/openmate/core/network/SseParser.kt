package com.openmate.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject

object SseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseLine(line: String): SseData? {
        if (!line.startsWith("data:")) return null
        val jsonStr = line.removePrefix("data:").trim()
        if (jsonStr.isEmpty()) return null
        val jsonObj = json.parseToJsonElement(jsonStr).jsonObject
        val type = jsonObj["type"]?.jsonPrimitive?.content ?: return null
        val properties = jsonObj["properties"]?.jsonObject ?: JsonObject(emptyMap())
        return SseData(
            type = type,
            properties = properties,
        )
    }

    fun parseChunk(chunk: String): List<SseData> {
        return chunk.lines()
            .filter { it.startsWith("data:") }
            .mapNotNull { parseLine(it) }
    }
}
