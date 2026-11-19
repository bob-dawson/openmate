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
        var attempts = 0
        while (messageCount > 0 && attempts < 4) {
            scroll(messageCount)
            pause(80)
            if (!canScrollForward()) break
            attempts++
        }
    } catch (_: NullPointerException) {
    } finally {
        onEnded()
    }
}
