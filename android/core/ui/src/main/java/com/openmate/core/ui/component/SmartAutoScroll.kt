package com.openmate.core.ui.component

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember

@Composable
fun SmartAutoScroll(
    listState: LazyListState,
    messageCount: Int,
    isLoading: Boolean,
) {
    val atExactBottom by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ==
                listState.layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(isLoading) {
        if (!isLoading && messageCount > 0) {
            listState.scrollToItem(messageCount - 1)
        }
    }

    LaunchedEffect(messageCount) {
        if (messageCount > 0 && !isLoading && atExactBottom) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }
}