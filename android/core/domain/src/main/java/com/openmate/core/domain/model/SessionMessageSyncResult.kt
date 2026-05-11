package com.openmate.core.domain.model

data class SessionMessageSyncResult(
    val lastSeq: Long,
    val changes: List<SessionMessageSyncChange>,
)

sealed interface SessionMessageSyncChange {
    data class Insert(val message: SessionMessage) : SessionMessageSyncChange
    data class Update(val message: SessionMessage) : SessionMessageSyncChange
    data class Remove(val messageId: String) : SessionMessageSyncChange
}
