package com.openmate.core.domain.model

data class SseEvent(
    val type: String,
    val payload: String,
)
