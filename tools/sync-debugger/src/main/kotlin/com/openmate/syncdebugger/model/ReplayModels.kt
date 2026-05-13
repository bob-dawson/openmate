package com.openmate.syncdebugger.model

import kotlinx.serialization.json.JsonObject

data class ReplayEvent(
    val id: String,
    val type: String,
    val data: JsonObject,
)

sealed class ReplayChange {
    data class Insert(val entity: SessionMessageEntity) : ReplayChange()
    data class Update(
        val id: String,
        val type: String,
        val data: JsonObject,
        val timeUpdated: Long,
        val completedAt: Long? = null,
        val roundMark: Boolean? = null,
    ) : ReplayChange()
}
