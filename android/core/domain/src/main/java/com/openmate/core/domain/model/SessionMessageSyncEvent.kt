package com.openmate.core.domain.model

data class SessionMessageSyncEvent(
    val sessionId: String,
    val result: SessionMessageSyncResult,
)
