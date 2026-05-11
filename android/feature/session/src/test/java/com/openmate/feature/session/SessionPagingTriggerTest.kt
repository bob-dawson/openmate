package com.openmate.feature.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionPagingTriggerTest {

    @Test
    fun shouldLoadOlder_returnsTrueWhenAtTopWithNewAnchor() {
        val state = SessionPagingTrigger.State(lastTriggeredFirstMessageId = "m2")

        val shouldLoad = SessionPagingTrigger.shouldLoadOlder(
            state = state,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            firstMessageId = "m3",
            hasOlderMessages = true,
            isLoadingOlder = false,
            shouldFollow = false,
        )

        assertThat(shouldLoad).isEqualTo(true)
    }

    @Test
    fun shouldLoadOlder_returnsFalseWhenRequestAlreadyTriggeredForAnchor() {
        val state = SessionPagingTrigger.State(lastTriggeredFirstMessageId = "m3")

        val shouldLoad = SessionPagingTrigger.shouldLoadOlder(
            state = state,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            firstMessageId = "m3",
            hasOlderMessages = true,
            isLoadingOlder = false,
            shouldFollow = false,
        )

        assertThat(shouldLoad).isEqualTo(false)
    }

    @Test
    fun shouldLoadOlder_returnsFalseWhenNotExactlyAtTop() {
        val state = SessionPagingTrigger.State(lastTriggeredFirstMessageId = null)

        val shouldLoad = SessionPagingTrigger.shouldLoadOlder(
            state = state,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 12,
            firstMessageId = "m3",
            hasOlderMessages = true,
            isLoadingOlder = false,
            shouldFollow = false,
        )

        assertThat(shouldLoad).isEqualTo(false)
    }

    @Test
    fun shouldLoadOlder_returnsFalseWhileAutoFollowingBottom() {
        val state = SessionPagingTrigger.State(lastTriggeredFirstMessageId = null)

        val shouldLoad = SessionPagingTrigger.shouldLoadOlder(
            state = state,
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            firstMessageId = "m3",
            hasOlderMessages = true,
            isLoadingOlder = false,
            shouldFollow = true,
        )

        assertThat(shouldLoad).isEqualTo(false)
    }

    @Test
    fun onTriggered_updatesAnchor() {
        val updated = SessionPagingTrigger.onTriggered(
            state = SessionPagingTrigger.State(lastTriggeredFirstMessageId = null),
            firstMessageId = "m3",
        )

        assertThat(updated.lastTriggeredFirstMessageId).isEqualTo("m3")
    }
}
