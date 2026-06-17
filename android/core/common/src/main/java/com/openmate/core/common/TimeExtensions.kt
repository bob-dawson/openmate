package com.openmate.core.common

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

fun Long.toRelativeTimeString(): String {
    val now = System.currentTimeMillis()
    val diff = now - this
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "just now"
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val mins = TimeUnit.MILLISECONDS.toMinutes(diff)
            "$mins min ago"
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            "$hours hr ago"
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            "$days day${if (days > 1) "s" else ""} ago"
        }
        else -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            "${days / 7} week${if (days / 7 > 1) "s" else ""} ago"
        }
    }
}

fun Long.toTimeString(): String {
    val sdf = SimpleDateFormat("HH:mm:ss")
    return sdf.format(this)
}

fun formatDurationMillis(
    millis: Long,
    formatHms: (h: Long, m: Long, s: Long) -> String = { h, m, s -> "${h}h ${m}m ${s}s" },
    formatMs: (m: Long, s: Long) -> String = { m, s -> "${m}m ${s}s" },
    formatS: (s: Long) -> String = { s -> "${s}s" },
): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> formatHms(hours, minutes, seconds)
        minutes > 0 -> formatMs(minutes, seconds)
        else -> formatS(seconds)
    }
}

fun Long.toDateTimeString(): String {
    val sdf = SimpleDateFormat("MM/dd HH:mm")
    return sdf.format(this)
}
