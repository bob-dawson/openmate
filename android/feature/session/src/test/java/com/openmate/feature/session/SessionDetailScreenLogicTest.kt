package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionDetailScreenLogicTest {

    @Test
    fun shouldScrollToBottomOnInitialLoad_returnsTrueWithoutSavedPosition() {
        assertThat(
            shouldScrollToBottomOnInitialLoad(
                messageCount = 5,
                previousMessageCount = 0,
                hasSavedScrollPosition = false,
            )
        ).isTrue()
    }

    @Test
    fun shouldScrollToBottomOnInitialLoad_returnsFalseWhenPositionWasRestored() {
        assertThat(
            shouldScrollToBottomOnInitialLoad(
                messageCount = 5,
                previousMessageCount = 0,
                hasSavedScrollPosition = true,
            )
        ).isFalse()
    }

    @Test
    fun syncLogMenuAndRegexFilterBehaviors() {
        assertThat(sessionDetailMenuItems()).contains("同步日志")

        val visible = filterRenderedLogs(
            renderedLogs = listOf(
                "12:00:00.000 INFO [Sse] SSE连接成功 trace=sse-1 message=connected",
                "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException",
            ),
            query = "socket.*exception",
            previousVisibleLogs = emptyList(),
        )

        assertThat(visible.visibleLogs).containsExactly(
            "12:00:01.000 ERROR [Sync] 增量同步失败 trace=inc-1 message=SocketTimeoutException",
        )
    }

}
