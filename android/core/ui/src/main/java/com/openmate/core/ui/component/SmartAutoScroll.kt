package com.openmate.core.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

@Composable
fun SmartAutoScroll(
    listState: LazyListState,
    messageCount: Int,
    isLoading: Boolean,
) {
    var followBottom by remember { mutableStateOf(true) }
    var autoScrolling by remember { mutableStateOf(false) }

    if (!listState.canScrollForward && !autoScrolling) followBottom = true

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && listState.canScrollForward && !autoScrolling) {
            followBottom = false
        }
    }

    val needScroll by remember {
        derivedStateOf { followBottom && listState.canScrollForward }
    }

    suspend fun scrollToBottom() {
        autoScrolling = true
        val idx = listState.layoutInfo.totalItemsCount - 1
        if (idx >= 0) {
            listState.animateScrollToItem(idx, 0)
        }
        delay(100)
        val idx2 = listState.layoutInfo.totalItemsCount - 1
        if (idx2 >= 0 && listState.canScrollForward) {
            listState.animateScrollToItem(idx2, 0)
        }
        followBottom = true
        autoScrolling = false
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && messageCount > 0) {
            scrollToBottom()
        }
    }

    LaunchedEffect(messageCount, needScroll) {
        if (messageCount > 0 && !isLoading && needScroll) {
            scrollToBottom()
        }
    }
}