package com.openmate.core.domain.model

data class LivePartEvent(
    val sessionId: String,
    val messageId: String,
    val partId: String,
    val eventType: String,
    val partType: String?,
    val text: String?,
    val isComplete: Boolean,
)
