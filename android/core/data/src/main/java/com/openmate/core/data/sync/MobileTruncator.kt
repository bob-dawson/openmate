package com.openmate.core.data.sync

import kotlinx.serialization.json.JsonObject

object MobileTruncator {
    fun truncate(type: String, data: JsonObject): JsonObject {
        return data
    }
}
