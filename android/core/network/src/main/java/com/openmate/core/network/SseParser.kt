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

        val directory = try { jsonObj["directory"]?.jsonPrimitive?.content } catch (_: Exception) { null }

        val payload = jsonObj["payload"]?.jsonObject ?: jsonObj

        val syncEvent = payload["syncEvent"]?.jsonObject
        if (syncEvent != null) {
            val versionedType = syncEvent["type"]?.jsonPrimitive?.content ?: return null
            val type = versionedType.substringBeforeLast('.')
            val data = syncEvent["data"]?.jsonObject ?: JsonObject(emptyMap())
            return SseData(type = type, properties = data, directory = directory)
        }

        val type = payload["type"]?.jsonPrimitive?.content ?: return null
        val properties = payload["properties"]?.jsonObject ?: JsonObject(emptyMap())
        return SseData(type = type, properties = properties, directory = directory)
    }

    fun parseChunk(chunk: String): List<SseData> {
        return chunk.lines()
            .filter { it.startsWith("data:") }
            .mapNotNull { parseLine(it) }
    }
}
