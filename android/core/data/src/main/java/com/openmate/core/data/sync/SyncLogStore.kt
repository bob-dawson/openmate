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
            (entries + entry).takeLast(500)
        }
    }

    fun log(
        level: SyncLogLevel,
        category: SyncLogCategory,
        message: String,
        sessionId: String? = null,
    ) {
        append(
            SyncLogEntry(
                id = nextId.getAndIncrement(),
                timestamp = System.currentTimeMillis(),
                level = level,
                category = category,
                sessionId = sessionId,
                message = message,
            )
        )
    }

    fun clear() {
        _entries.update { emptyList() }
    }
}