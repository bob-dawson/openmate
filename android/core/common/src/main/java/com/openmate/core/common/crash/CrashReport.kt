package com.openmate.core.common.crash

data class CrashReport(
    val timestamp: Long,
    val threadName: String,
    val exceptionClass: String,
    val message: String?,
    val stackTrace: String,
    val deviceInfo: String,
)
