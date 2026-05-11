package com.openmate.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutoFollowTrackerTest {

    @Test
    fun initial_shouldFollowIsTrue() {
        val tracker = AutoFollowTracker()
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun initial_consumeShouldScrollToBottomIsFalse() {
        val tracker = AutoFollowTracker()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun messagesChanged_zeroCount_noScrollRequest() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(0, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun initialLoad_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun initialLoad_loading_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, true)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun initialLoad_loadingThenContentUpdated_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, true)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun newMessage_following_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onMessagesChanged(6, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun newMessage_loading_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onMessagesChanged(6, true)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun newMessage_notFollowing_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(true)
        tracker.onMessagesChanged(6, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun userScrollUp_stopsFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onScrollStarted(true)
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun scrollPositionChanged_whileDraggingAwayFromBottom_stopsFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onScrollStarted(false)
        assertThat(tracker.shouldFollow).isTrue()
        tracker.onScrollPositionChanged(true)
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun shouldAutoFollow_whileUserIsDragging_isFalse() {
        val tracker = AutoFollowTracker()
        assertThat(tracker.shouldAutoFollow(canScrollForward = true, isScrollInProgress = true)).isFalse()
    }

    @Test
    fun userScrollBackToBottom_resumesFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onScrollStarted(true)
        tracker.onScrollStopped(false)
        assertThat(tracker.shouldFollow).isTrue()
    }

    @Test
    fun userScrolledUp_newMessage_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onScrollStarted(true)
        tracker.onMessagesChanged(6, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun autoScrollStarted_ignoresScrollStarted() {
        val tracker = AutoFollowTracker()
        tracker.onAutoScrollStarted()
        tracker.onScrollStarted(true)
        assertThat(tracker.shouldFollow).isTrue()
        tracker.onAutoScrollEnded()
    }

    @Test
    fun autoScrollStarted_ignoresScrollStopped() {
        val tracker = AutoFollowTracker()
        tracker.onScrollStarted(true)
        assertThat(tracker.shouldFollow).isFalse()
        tracker.onAutoScrollStarted()
        tracker.onScrollStopped(false)
        assertThat(tracker.shouldFollow).isFalse()
        tracker.onAutoScrollEnded()
    }

    @Test
    fun keyboardAnimating_ignoresScrollStarted() {
        val tracker = AutoFollowTracker()
        tracker.onKeyboardAnimationStarted()
        tracker.onScrollStarted(true)
        assertThat(tracker.shouldFollow).isTrue()
        tracker.onKeyboardAnimationEnded()
    }

    @Test
    fun keyboardEnded_following_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onKeyboardAnimationStarted()
        tracker.onKeyboardAnimationEnded()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun keyboardEnded_notFollowing_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onScrollStarted(true)
        tracker.onKeyboardAnimationStarted()
        tracker.onKeyboardAnimationEnded()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun keyboardAnimating_ignoresScrollStopped() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onKeyboardAnimationStarted()
        tracker.onScrollStopped(false)
        tracker.onKeyboardAnimationEnded()
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun navigateToMessage_stopsFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onNavigateToMessage()
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun navigating_ignoresScrollStopped() {
        val tracker = AutoFollowTracker()
        tracker.onNavigateToMessage()
        tracker.onScrollStopped(false)
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun navigateComplete_staysNotFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        assertThat(tracker.shouldFollow).isFalse()
    }

    @Test
    fun afterNavigate_newMessage_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        tracker.onMessagesChanged(6, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun afterNavigate_scrollBackToBottom_resumesFollowing() {
        val tracker = AutoFollowTracker()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        tracker.onScrollStopped(false)
        assertThat(tracker.shouldFollow).isTrue()
    }

    @Test
    fun afterNavigate_requestFollow_resumesAndScrolls() {
        val tracker = AutoFollowTracker()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        tracker.onRequestFollow()
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun localMessageSent_resumesFollowingAndTriggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onLocalMessageSent()

        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun localMessageSent_notFollowing_doesNotForceFollow() {
        val tracker = AutoFollowTracker()
        tracker.onScrollStarted(true)

        tracker.onLocalMessageSent()

        assertThat(tracker.shouldFollow).isFalse()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun contentUpdated_following_triggersScroll() {
        val tracker = AutoFollowTracker()
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun contentUpdated_afterInitialLoadStillTriggersScrollWithoutCountChange() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()

        tracker.onContentUpdated()

        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun contentUpdated_notFollowing_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onScrollStarted(true)
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun contentUpdated_navigating_noScroll() {
        val tracker = AutoFollowTracker()
        tracker.onNavigateToMessage()
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun consumeScrollRequest_pulseResetsAfterConsume() {
        val tracker = AutoFollowTracker()
        tracker.onContentUpdated()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
    }

    @Test
    fun fullFlow_load_scrollUp_newMessage_scrollBack_newMessage() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
        tracker.onScrollStarted(true)
        assertThat(tracker.shouldFollow).isFalse()
        tracker.onMessagesChanged(6, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
        tracker.onScrollStopped(false)
        assertThat(tracker.shouldFollow).isTrue()
        tracker.onMessagesChanged(7, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun fullFlow_load_navigate_complete_newMessage_button_newMessage() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onNavigateToMessage()
        tracker.onNavigateComplete()
        tracker.onMessagesChanged(6, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isFalse()
        tracker.onRequestFollow()
        assertThat(tracker.shouldFollow).isTrue()
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
        tracker.onMessagesChanged(7, false)
        assertThat(tracker.consumeShouldScrollToBottom()).isTrue()
    }

    @Test
    fun scrollVersion_incrementsOnRequestScroll() {
        val tracker = AutoFollowTracker()
        assertThat(tracker.scrollVersion).isEqualTo(0)
        tracker.onMessagesChanged(5, false)
        assertThat(tracker.scrollVersion).isEqualTo(1)
        tracker.onContentUpdated()
        assertThat(tracker.scrollVersion).isEqualTo(2)
    }

    @Test
    fun nested_autoScrollAndKeyboard_stateUnchanged() {
        val tracker = AutoFollowTracker()
        tracker.onMessagesChanged(5, false)
        tracker.consumeShouldScrollToBottom()
        tracker.onAutoScrollStarted()
        tracker.onKeyboardAnimationStarted()
        tracker.onScrollStarted(true)
        tracker.onScrollStopped(false)
        tracker.onKeyboardAnimationEnded()
        tracker.onAutoScrollEnded()
        assertThat(tracker.shouldFollow).isTrue()
    }
}
