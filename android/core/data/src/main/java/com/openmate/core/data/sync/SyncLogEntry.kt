package com.openmate.core.data.sync

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SyncLogLevel { Info, Warn, Error }

enum class SyncLogCategory { Sse, Sync, Manual, Poll }

data class SyncLogEntry(
    val id: Long,
    val timestamp: Long,
    val level: SyncLogLevel,
    val category: SyncLogCategory,
    val sessionId: String?,
    val title: String,
    val message: String,
    val bytes: Int?,
    val relatedSeq: Long?,
    val traceId: String?,
) {
    val renderedText: String
        get() {
            val timeText = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            return buildList {
                add(timeText)
                add(level.name.uppercase())
                add("[${category.name}]")
                add(title)
                sessionId?.let { add("session=$it") }
                traceId?.let { add("trace=$it") }
                relatedSeq?.let { add("seq=$it") }
                bytes?.let { add("bytes=$it") }
                add("message=$message")
            }.joinToString(" ")
        }
}
