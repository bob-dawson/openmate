package com.openmate.feature.session

data class SyncLogFilterResult(
    val visibleLogs: List<String>,
    val regexError: String?,
)

fun shouldAutoFollowSyncLogs(
    autoFollowEnabled: Boolean,
    previousLogCount: Int,
    currentLogCount: Int,
): Boolean = autoFollowEnabled && currentLogCount > previousLogCount

fun shouldExecuteSyncLogClear(
    isConfirming: Boolean,
    confirmClicked: Boolean,
): Boolean = isConfirming && confirmClicked

fun nextSyncLogClearState(
    isConfirming: Boolean,
    confirmClicked: Boolean,
): Boolean = if (confirmClicked) !isConfirming else false

fun filterRenderedLogs(
    renderedLogs: List<String>,
    query: String,
    previousVisibleLogs: List<String>,
    regexErrorMessage: String = "Invalid regex",
): SyncLogFilterResult {
    if (query.isBlank()) return SyncLogFilterResult(renderedLogs, null)
    return try {
        val regex = Regex(query, setOf(RegexOption.IGNORE_CASE))
        SyncLogFilterResult(renderedLogs.filter { regex.containsMatchIn(it) }, null)
    } catch (_: IllegalArgumentException) {
        SyncLogFilterResult(previousVisibleLogs, regexErrorMessage)
    }
}
