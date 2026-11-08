package com.openmate.feature.session

import com.openmate.core.domain.model.SessionMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 压缩消息的完成状态由服务端的 `status` 表示，`time.completed` 不存在。
 * 老数据里 completedAt 为空但内容已生成，按已完成展示（完成时间取创建时间）。
 */
object SessionMessageCompletion {

    fun displayCompletedAt(message: SessionMessage): Long? =
        displayCompletedAt(message.completedAt, message.type, parse(message.data), message.timeCreated)

    fun displayCompletedAt(
        completedAt: Long?,
        type: String,
        data: JsonObject?,
        timeCreated: Long,
    ): Long? {
        completedAt?.let { return it }
        if (type != "compaction") return null
        val status = data?.get("status")?.jsonPrimitive?.contentOrNull
        if (status == "completed" || status == "failed") return timeCreated
        val summary = data?.get("summary")?.jsonPrimitive?.contentOrNull.orEmpty()
        val recent = data?.get("recent")?.jsonPrimitive?.contentOrNull.orEmpty()
        return if (summary.isNotBlank() || recent.isNotBlank()) timeCreated else null
    }

    fun parse(data: String): JsonObject? =
        runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
}
