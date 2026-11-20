package com.openmate.core.common

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit

enum class RelativeTimeUnit {
    JustNow,
    Minutes,
    Hours,
    Days,
    Weeks,
}

data class RelativeTime(
    val unit: RelativeTimeUnit,
    val value: Long = 0,
)

fun Long.toRelativeTime(now: Long = System.currentTimeMillis()): RelativeTime {
    val diff = (now - this).coerceAtLeast(0)
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> RelativeTime(RelativeTimeUnit.JustNow)
        diff < TimeUnit.HOURS.toMillis(1) -> RelativeTime(
            RelativeTimeUnit.Minutes,
            TimeUnit.MILLISECONDS.toMinutes(diff),
        )
        diff < TimeUnit.DAYS.toMillis(1) -> RelativeTime(
            RelativeTimeUnit.Hours,
            TimeUnit.MILLISECONDS.toHours(diff),
        )
        diff < TimeUnit.DAYS.toMillis(7) -> RelativeTime(
            RelativeTimeUnit.Days,
            TimeUnit.MILLISECONDS.toDays(diff),
        )
        else -> RelativeTime(
            RelativeTimeUnit.Weeks,
            TimeUnit.MILLISECONDS.toDays(diff) / 7,
        )
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
