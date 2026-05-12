package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SyncLogFilterLogicTest {

    @Test
    fun filterLogs_usesRenderedTextRegex_andKeepsPreviousResultsOnInvalidPattern() {
        val source = listOf(
            "15:42:18.892 INFO [Sync] 增量包返回 session=ses_a trace=inc-1 bytes=18234 message=events response received",
            "15:48:12.312 ERROR [Sync] 增量同步失败 session=ses_b trace=inc-2 message=SocketTimeoutException",
        )

        val matched = filterRenderedLogs(
            renderedLogs = source,
            query = "socket.*exception",
            previousVisibleLogs = source,
        )
        assertThat(matched.visibleLogs).containsExactly(source[1])

        val invalid = filterRenderedLogs(
            renderedLogs = source,
            query = "[abc",
            previousVisibleLogs = matched.visibleLogs,
        )
        assertThat(invalid.visibleLogs).containsExactly(source[1])
        assertThat(invalid.regexError).isEqualTo("正则无效")
    }

    @Test
    fun shouldAutoFollowSyncLogs_onlyWhenEnabledAndNewLogsAppended() {
        assertThat(
            shouldAutoFollowSyncLogs(
                autoFollowEnabled = true,
                previousLogCount = 1,
                currentLogCount = 2,
            )
        ).isEqualTo(true)

        assertThat(
            shouldAutoFollowSyncLogs(
                autoFollowEnabled = false,
                previousLogCount = 1,
                currentLogCount = 2,
            )
        ).isEqualTo(false)

        assertThat(
            shouldAutoFollowSyncLogs(
                autoFollowEnabled = true,
                previousLogCount = 2,
                currentLogCount = 2,
            )
        ).isEqualTo(false)
    }

    @Test
    fun syncLogClearConfirmation_requiresSecondTapToConfirm() {
        assertThat(nextSyncLogClearState(isConfirming = false, confirmClicked = true)).isEqualTo(true)
        assertThat(nextSyncLogClearState(isConfirming = true, confirmClicked = false)).isEqualTo(false)
        assertThat(shouldExecuteSyncLogClear(isConfirming = false, confirmClicked = true)).isEqualTo(false)
        assertThat(shouldExecuteSyncLogClear(isConfirming = true, confirmClicked = true)).isEqualTo(true)
    }
}
