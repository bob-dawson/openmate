package com.openmate.core.domain.model

data class SessionMessage(
    val id: String,
    val sessionId: String,
    val type: String,
    val data: String,
    val timeCreated: Long,
    val timeUpdated: Long,
    val completedAt: Long? = null,
)
