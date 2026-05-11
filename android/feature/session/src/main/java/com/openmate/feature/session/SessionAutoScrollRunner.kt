package com.openmate.feature.session

import kotlinx.coroutines.delay

internal suspend fun runAutoScroll(
    messageCount: Int,
    canScrollForward: () -> Boolean,
    onStarted: () -> Unit,
    onEnded: () -> Unit,
    pause: suspend (Long) -> Unit = { delay(it) },
    scroll: suspend (Int) -> Unit,
) {
    onStarted()
    try {
        if (messageCount > 0) {
            scroll(messageCount)
        }
        pause(100)
        if (messageCount > 0 && canScrollForward()) {
            scroll(messageCount)
        }
    } catch (_: NullPointerException) {
    } finally {
        onEnded()
    }
}
