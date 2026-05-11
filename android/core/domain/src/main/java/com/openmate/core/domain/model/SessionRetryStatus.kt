package com.openmate.core.domain.model

data class SessionRetryStatus(
    val sessionId: String,
    val attempt: Int?,
    val message: String,
    val next: Long?,
)
