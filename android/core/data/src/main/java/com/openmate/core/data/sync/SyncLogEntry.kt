package com.openmate.core.data.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncLogLevel { Info, Warn, Error }

enum class SyncLogCategory { Sse, Sync, Manual, Poll, Gateway, Connection }

data class SyncLogEntry(
    val id: Long,
    val timestamp: Long,
    val level: SyncLogLevel,
    val category: SyncLogCategory,
    val sessionId: String?,
    val message: String,
) {
    val renderedText: String
        get() {
            val timeText = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val sessionPart = sessionId?.let { "($it)" } ?: ""
            return "$timeText ${level.name.uppercase()} [${category.name}] $sessionPart $message"
        }
}