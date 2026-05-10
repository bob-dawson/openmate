package com.openmate.core.common

class AutoFollowTracker {

    var shouldFollow: Boolean = true
        private set

    var scrollVersion: Int = 0
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
