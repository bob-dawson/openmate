package com.openmate.syncdebugger.model

data class SessionMessageEntity(
    val id: String,
    val sessionId: String,
    val type: String,
    val data: String,
    val timeCreated: Long,
    val timeUpdated: Long,
    val completedAt: Long? = null,
    val roundMark: Boolean = true,
)

data class SyncStateEntity(
    val sessionId: String,
    val lastSeq: Long,
)
