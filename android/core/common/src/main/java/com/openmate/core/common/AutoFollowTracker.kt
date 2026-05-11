package com.openmate.core.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class AutoFollowTracker {

    var shouldFollow by mutableStateOf(true)
        private set

    var scrollVersion by mutableIntStateOf(0)
        private set

    private var prevMessageCount: Int = 0
    private var pendingScrollRequest: Boolean = false
    private var isAutoScrolling: Boolean = false
    private var isKeyboardAnimating: Boolean = false
    private var isUserNavigating: Boolean = false

    fun onMessagesChanged(count: Int, isLoading: Boolean) {
        if (count == 0) {
            prevMessageCount = 0
            return
        }
        if (prevMessageCount == 0) {
            prevMessageCount = count
            if (!isLoading) {
                requestScroll()
            }
            return
        }
        prevMessageCount = count
        if (shouldFollow && !isLoading && !isUserNavigating) {
            requestScroll()
        }
    }

    fun onScrollStarted(canScrollForward: Boolean) {
        if (isAutoScrolling || isKeyboardAnimating || isUserNavigating) return
        if (canScrollForward) {
            shouldFollow = false
        }
    }

    fun onScrollStopped(canScrollForward: Boolean) {
        if (isAutoScrolling || isKeyboardAnimating || isUserNavigating) return
        if (!canScrollForward) {
            shouldFollow = true
        }
    }

    fun onScrollPositionChanged(canScrollForward: Boolean) {
        if (isAutoScrolling || isKeyboardAnimating || isUserNavigating) return
        if (canScrollForward) {
            shouldFollow = false
        }
    }

    fun onKeyboardAnimationStarted() {
        isKeyboardAnimating = true
    }

    fun onKeyboardAnimationEnded() {
        isKeyboardAnimating = false
        if (shouldFollow) {
            requestScroll()
        }
    }

    fun onNavigateToMessage() {
        shouldFollow = false
        isUserNavigating = true
    }

    fun onNavigateComplete() {
        isUserNavigating = false
    }

    fun onRequestFollow() {
        shouldFollow = true
        requestScroll()
    }

    fun onLocalMessageSent() {
        if (shouldFollow) {
            requestScroll()
        }
    }

    fun onAutoScrollStarted() {
        isAutoScrolling = true
    }

    fun onAutoScrollEnded() {
        isAutoScrolling = false
    }

    fun onContentUpdated() {
        if (shouldFollow && !isUserNavigating) {
            requestScroll()
        }
    }

    fun shouldAutoFollow(canScrollForward: Boolean, isScrollInProgress: Boolean): Boolean {
        return shouldFollow && canScrollForward && !isScrollInProgress
    }

    fun consumeShouldScrollToBottom(): Boolean {
        if (pendingScrollRequest) {
            pendingScrollRequest = false
            return true
        }
        return false
    }

    private fun requestScroll() {
        pendingScrollRequest = true
        scrollVersion++
    }
}
