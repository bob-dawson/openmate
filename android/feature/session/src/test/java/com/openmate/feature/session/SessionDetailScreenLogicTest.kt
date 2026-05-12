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
}
