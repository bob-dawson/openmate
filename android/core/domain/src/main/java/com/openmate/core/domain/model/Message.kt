package com.openmate.core.domain.model

data class Message(
    val id: String,
    val sessionID: String,
    val role: MessageRole,
    val agent: String? = null,
    val createdAt: Long,
    val parts: List<Part> = emptyList(),
)
