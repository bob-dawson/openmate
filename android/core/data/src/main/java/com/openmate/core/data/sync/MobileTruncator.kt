package com.openmate.core.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object MobileTruncator {
    private const val TEXT_KEEP = 1500

    fun truncate(type: String, data: JsonObject): JsonObject {
        return when (type) {
            "user" -> truncateUser(data)
            "assistant" -> truncateAssistant(data)
            else -> data
        }
    }

    private fun truncateUser(data: JsonObject): JsonObject {
        val text = data["text"]?.let { (it as? JsonPrimitive)?.content }
        val truncatedText = text?.let { truncateEndsChars(it, TEXT_KEEP) }
        return buildJsonObject {
            for ((key, value) in data) {
                if (key == "text" && truncatedText != null) {
                    put("text", truncatedText)
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun truncateAssistant(data: JsonObject): JsonObject {
        val content = data["content"]?.let { it as? kotlinx.serialization.json.JsonArray }
        if (content == null) return data

        val truncatedContent = content.map { element ->
            val obj = element as? JsonObject ?: return@map element
            val partType = obj["type"]?.let { (it as? JsonPrimitive)?.content } ?: return@map obj
            when (partType) {
                "text", "reasoning" -> {
                    val text = obj["text"]?.let { (it as? JsonPrimitive)?.content } ?: return@map obj
                    val truncated = truncateEndsChars(text, TEXT_KEEP)
                    buildJsonObject {
                        for ((key, value) in obj) {
                            if (key == "text") {
                                put("text", truncated)
                            } else {
                                put(key, value)
                            }
                        }
                    }
                }
                else -> obj
            }
        }

        return buildJsonObject {
            for ((key, value) in data) {
                if (key == "content") {
                    put("content", kotlinx.serialization.json.JsonArray(truncatedContent))
                } else {
                    put(key, value)
                }
            }
        }
    }

    fun truncateEndsChars(text: String, keep: Int): String {
        if (text.length <= keep * 2) return text
        val head = text.take(keep)
        val tail = text.takeLast(keep)
        return "$head...[truncated]...$tail"
    }
}
