package com.openmate.core.common.crash

import android.content.Context
import android.os.Build
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class CrashHandler(
    private val context: Context,
) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val crashDir by lazy { File(context.filesDir, "crashes").also { it.mkdirs() } }

    companion object {
        private const val MAX_CRASH_FILES = 10

        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext))
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        saveCrash(thread, throwable)
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrash(thread: Thread, throwable: Throwable) {
        try {
            trimOldFiles()
            val timestamp = System.currentTimeMillis()
            val report = CrashReport(
                timestamp = timestamp,
                threadName = thread.name,
                exceptionClass = throwable.javaClass.name,
                message = throwable.message,
                stackTrace = throwable.stackTraceToString(),
                deviceInfo = buildDeviceInfo(),
            )
            val file = File(crashDir, "crash_$timestamp.log")
            file.writeText(formatReport(report))
        } catch (_: Exception) {}
    }

    private fun trimOldFiles() {
        val files = crashDir.listFiles()?.sortedBy { it.name } ?: return
        if (files.size >= MAX_CRASH_FILES) {
            val toDelete = files.take(files.size - MAX_CRASH_FILES + 1)
            toDelete.forEach { it.delete() }
        }
    }

    private fun buildDeviceInfo(): String {
        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) { "unknown" }
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), App $versionName"
    }

    private fun formatReport(report: CrashReport): String {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(report.timestamp))
        return buildString {
            appendLine("Time: $time")
            appendLine("Thread: ${report.threadName}")
            appendLine("Device: ${report.deviceInfo}")
            appendLine()
            appendLine("${report.exceptionClass}: ${report.message ?: ""}")
            append(report.stackTrace)
        }
    }
}
