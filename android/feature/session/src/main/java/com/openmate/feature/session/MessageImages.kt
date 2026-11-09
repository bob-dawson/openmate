package com.openmate.feature.session

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object MessageImages {

    fun imageFiles(data: JsonObject): List<JsonObject> {
        val files = data["files"] as? JsonArray ?: return emptyList()
        return files.mapNotNull { it as? JsonObject }.filter { isImage(it) }
    }

    fun base64At(data: JsonObject, index: Int): String? {
        val file = imageFiles(data).getOrNull(index) ?: return null
        return (file["data"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    }

    fun isImage(file: JsonObject): Boolean {
        val mime = (file["mime"] as? JsonPrimitive)?.content ?: return false
        return mime.startsWith("image/")
    }
}
