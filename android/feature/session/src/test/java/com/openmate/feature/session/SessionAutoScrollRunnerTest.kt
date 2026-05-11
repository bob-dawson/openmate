package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SessionAutoScrollRunnerTest {

    @Test
    fun runAutoScroll_callsCleanupWhenScrollThrows() = runBlocking {
        var started = 0
        var ended = 0
        var scrollCalls = 0

        runAutoScroll(
            messageCount = 3,
            canScrollForward = { true },
            onStarted = { started++ },
            onEnded = { ended++ },
            pause = {},
            scroll = {
                scrollCalls++
                throw NullPointerException("disposed list state")
            },
        )

        assertThat(started).isEqualTo(1)
        assertThat(ended).isEqualTo(1)
        assertThat(scrollCalls).isEqualTo(1)
    }

    @Test
    fun runAutoScroll_scrollsTwiceWhenStillNotAtBottom() = runBlocking {
        var started = 0
        var ended = 0
        val indices = mutableListOf<Int>()

        runAutoScroll(
            messageCount = 3,
            canScrollForward = { true },
            onStarted = { started++ },
            onEnded = { ended++ },
            pause = {},
            scroll = { index -> indices += index },
        )

        assertThat(started).isEqualTo(1)
        assertThat(ended).isEqualTo(1)
        assertThat(indices).containsExactly(3, 3).inOrder()
    }
}
