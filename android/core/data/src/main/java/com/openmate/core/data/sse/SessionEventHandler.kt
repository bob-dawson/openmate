package com.openmate.core.data.sse

import com.openmate.core.database.ActiveDatabaseProvider
import com.openmate.core.database.entity.toEntity
import com.openmate.core.network.SseData
import com.openmate.core.network.dto.SessionDto
import com.openmate.core.network.dto.toDomain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

open class SessionEventHandler @Inject constructor() {
    open fun handle(type: String, event: SseData) {}
}
