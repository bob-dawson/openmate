package com.openmate.core.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
fun SmartAutoScroll(
    listState: LazyListState,
    messageCount: Int,
    isLoading: Boolean,
    userNavigating: Boolean = false,
    imeAnimating: Boolean = false,
) {
    var followBottom by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }
    var prevCount by remember { mutableIntStateOf(0) }
    var imeWasFollowing by remember { mutableStateOf(true) }

    LaunchedEffect(userNavigating) {
        if (userNavigating) followBottom = false
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!autoScrolling && !imeAnimating) {
            if (listState.isScrollInProgress) {
                if (listState.canScrollForward) {
                    followBottom = false
                }
            } else {
                if (!listState.canScrollForward) {
                    followBottom = true
                }
            }
        }
    }

    val needScroll by remember {
        derivedStateOf { followBottom && listState.canScrollForward }
    }

    suspend fun scrollToBottom() {
        autoScrolling = true
        if (messageCount > 0) {
            listState.animateScrollToItem(messageCount)
        }
        delay(100)
        if (messageCount > 0 && listState.canScrollForward) {
            listState.animateScrollToItem(messageCount)
        }
        followBottom = true
        autoScrolling = false
    }

    LaunchedEffect(messageCount) {
        if (messageCount > 0) {
            val isInitialLoad = prevCount == 0
            prevCount = messageCount
            if (isInitialLoad) {
                delay(150)
                scrollToBottom()
            } else if (!isLoading && followBottom && !userNavigating) {
                scrollToBottom()
            }
        } else {
            prevCount = messageCount
        }
    }

    LaunchedEffect(needScroll) {
        if (messageCount > 0 && !isLoading && needScroll && !userNavigating) {
            scrollToBottom()
        }
    }

    LaunchedEffect(imeAnimating) {
        if (imeAnimating) {
            imeWasFollowing = followBottom
        } else if (imeWasFollowing && messageCount > 0) {
            scrollToBottom()
        }
    }
}
