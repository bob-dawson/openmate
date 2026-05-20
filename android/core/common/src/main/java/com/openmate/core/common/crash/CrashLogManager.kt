package com.openmate.core.common.crash

import android.content.Context
import android.content.SharedPreferences
import java.io.File

class CrashLogManager(
    private val crashDir: File,
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "crashes").also { it.mkdirs() },
        context.getSharedPreferences("crash_log", Context.MODE_PRIVATE),
    )

    companion object {
        private const val KEY_LAST_READ = "crash_last_read"
        private const val FILE_PREFIX = "crash_"
        private const val FILE_SUFFIX = ".log"
    }

    fun getReports(): List<CrashReport> {
        return crashDir.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.mapNotNull { parseReport(it) }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun getUnreadCount(): Int {
        val lastRead = prefs.getLong(KEY_LAST_READ, 0L)
        return crashDir.listFiles()
            ?.count { f ->
                f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) &&
                    extractTimestamp(f.name) > lastRead
            } ?: 0
    }

    fun markAllRead() {
        val latest = crashDir.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.mapNotNull { extractTimestamp(it.name) }
            ?.maxOrNull() ?: return
        prefs.edit().putLong(KEY_LAST_READ, latest).apply()
    }

    fun deleteReport(timestamp: Long) {
        File(crashDir, "$FILE_PREFIX$timestamp$FILE_SUFFIX").delete()
    }

    fun deleteAllReports() {
        crashDir.listFiles()
            ?.filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            ?.forEach { it.delete() }
    }

    fun getReportContent(timestamp: Long): String? {
        val file = File(crashDir, "$FILE_PREFIX$timestamp$FILE_SUFFIX")
        return if (file.exists()) file.readText() else null
    }

    private fun extractTimestamp(name: String): Long {
        return name.removePrefix(FILE_PREFIX).removeSuffix(FILE_SUFFIX).toLongOrNull() ?: 0L
    }

    private fun parseReport(file: File): CrashReport? {
        val timestamp = extractTimestamp(file.name)
        if (timestamp == 0L) return null
        val content = file.readText()
        val lines = content.lines()
        val threadName = lines.firstOrNull { it.startsWith("Thread: ") }?.removePrefix("Thread: ") ?: "unknown"
        val deviceInfo = lines.firstOrNull { it.startsWith("Device: ") }?.removePrefix("Device: ") ?: ""
        val exceptionLine = lines.firstOrNull {
            it.isNotBlank() && !it.startsWith("Time:") && !it.startsWith("Thread:") &&
                !it.startsWith("Device:") && !it.startsWith(" ") && !it.startsWith("\t") &&
                !it.startsWith("at ") && !it.startsWith("Caused")
        }
        val exceptionClass = exceptionLine?.substringBefore(":")?.trim() ?: "Unknown"
        val message = exceptionLine?.substringAfter(":", "")?.trim()?.ifBlank { null }
        val stackTraceStart = lines.indexOfFirst { it.startsWith("at ") || it.startsWith("Caused by:") }.let { idx ->
            if (idx >= 0) lines.subList(idx, lines.size).joinToString("\n") else ""
        }
        return CrashReport(
            timestamp = timestamp,
            threadName = threadName,
            exceptionClass = exceptionClass,
            message = message,
            stackTrace = stackTraceStart,
            deviceInfo = deviceInfo,
        )
    }
}
