package com.openmate.core.data.sync

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Singleton
class SyncLogStore @Inject constructor() {
    private val nextId = AtomicLong(1)
    private val _entries = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val entries: StateFlow<List<SyncLogEntry>> = _entries.asStateFlow()

    fun append(entry: SyncLogEntry) {
        _entries.update { entries ->
            (entries + entry).takeLast(2000)
        }
    }

    fun log(
        level: SyncLogLevel,
        category: SyncLogCategory,
        sessionId: String? = null,
        title: String,
        message: String,
        bytes: Int? = null,
        relatedSeq: Long? = null,
        traceId: String? = null,
    ) {
        append(
            SyncLogEntry(
                id = nextId.getAndIncrement(),
                timestamp = System.currentTimeMillis(),
                level = level,
                category = category,
                sessionId = sessionId,
                title = title,
                message = message,
                bytes = bytes,
                relatedSeq = relatedSeq,
                traceId = traceId,
            )
        )
    }

    fun clear() {
        _entries.update { emptyList() }
    }
}
