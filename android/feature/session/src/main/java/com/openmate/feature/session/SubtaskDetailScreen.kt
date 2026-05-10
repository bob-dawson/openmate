package com.openmate.feature.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openmate.feature.session.R
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.common.AutoFollowTracker
import com.openmate.core.ui.component.TopBar
import com.openmate.feature.session.component.ChatInputBar
import com.openmate.feature.session.component.SessionMessageRenderer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtaskDetailScreen(
    subtaskSessionID: String,
    title: String,
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val autoFollowTracker = remember { AutoFollowTracker() }

    LaunchedEffect(subtaskSessionID) {
        viewModel.loadSession(subtaskSessionID)
    }

    val prevMessageCount = remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.size) {
        autoFollowTracker.onMessagesChanged(messages.size, isLoading)
        if (messages.size > 0 && prevMessageCount.intValue == 0) {
            kotlinx.coroutines.delay(150)
        }
        prevMessageCount.intValue = messages.size
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            autoFollowTracker.onScrollStarted(listState.canScrollForward)
        } else {
            autoFollowTracker.onScrollStopped(listState.canScrollForward)
        }
    }

    suspend fun scrollToBottom() {
        autoFollowTracker.onAutoScrollStarted()
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
        kotlinx.coroutines.delay(100)
        if (messages.isNotEmpty() && listState.canScrollForward) {
            listState.animateScrollToItem(messages.size)
        }
        autoFollowTracker.onAutoScrollEnded()
    }

    LaunchedEffect(autoFollowTracker.scrollVersion) {
        if (autoFollowTracker.consumeShouldScrollToBottom()) {
            scrollToBottom()
        }
    }

    val needFollowScroll by remember {
        derivedStateOf { autoFollowTracker.shouldFollow && listState.canScrollForward }
    }
    LaunchedEffect(needFollowScroll) {
        if (needFollowScroll && !autoFollowTracker.consumeShouldScrollToBottom()) {
            scrollToBottom()
        }
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopBar(
                title = title,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .imePadding(),
        ) {
            if (isStreaming) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    items(messages, key = { it.id }) { entity ->
                        SessionMessageRenderer(
                            entity = entity,
                            onFullContentRequest = { messageId ->
                                viewModel.fetchFullContent(subtaskSessionID, messageId)
                            },
                        )
                    }
                    if (messages.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = stringResource(R.string.no_messages),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            ChatInputBar(
                text = inputText,
                onTextChange = { viewModel.updateInput(it) },
                onSend = { viewModel.sendMessage(subtaskSessionID) },
            )
        }
    }
}
