package com.openmate.feature.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.ui.component.TopBar
import com.openmate.feature.session.component.ChatInputBar
import com.openmate.feature.session.component.MessageItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionID: String,
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val inputText by viewModel.inputText.collectAsState()
    val pendingPermissions by viewModel.pendingPermissions.collectAsState()
    val pendingQuestions by viewModel.pendingQuestions.collectAsState()
    val sessionTitle by viewModel.sessionTitle.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(sessionID) {
        viewModel.loadSession(sessionID)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    pendingPermissions.firstOrNull()?.let { perm ->
        PermissionDialog(
            request = perm,
            onAllow = {
                viewModel.replyPermission(
                    perm.id,
                    com.openmate.core.domain.model.PermissionReply.ONCE,
                    null,
                )
            },
            onDeny = {
                viewModel.replyPermission(
                    perm.id,
                    com.openmate.core.domain.model.PermissionReply.REJECT,
                    null,
                )
            },
            onDismiss = {},
        )
    }

    pendingQuestions.firstOrNull()?.let { question ->
        QuestionDialog(
            request = question,
            onSubmit = { answers -> viewModel.replyQuestion(question.id, answers) },
            onReject = { viewModel.rejectQuestion(question.id) },
            onDismiss = {},
        )
    }

    Scaffold(
        topBar = {
            TopBar(title = sessionTitle.ifBlank { "Chat" }, onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageItem(message = message)
                }
            }

            ChatInputBar(
                text = inputText,
                onTextChange = { viewModel.updateInput(it) },
                onSend = { viewModel.sendMessage(sessionID) },
                onAbort = { viewModel.abort(sessionID) },
                isStreaming = isStreaming,
            )
        }
    }
}
