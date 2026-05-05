package com.openmate.feature.session.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.model.MessageRole
import com.openmate.core.domain.model.Part
import com.openmate.core.common.toDateTimeString
import com.openmate.feature.session.R

private fun messageSummary(message: Message, maxLen: Int = 60): String {
    val textParts = message.parts
        .filterIsInstance<Part.TextPart>()
        .filter { !it.synthetic }
        .map { it.text.trim() }
    if (textParts.isNotEmpty()) {
        val full = textParts.joinToString(" ")
        return if (full.length <= maxLen) full else "${full.take(maxLen)}…"
    }
    val toolParts = message.parts.filterIsInstance<Part.ToolInvocationPart>()
    if (toolParts.isNotEmpty()) {
        return toolParts.first().toolName
    }
    return "—"
}

private enum class SearchTab(val labelResId: Int) {
    MY_MESSAGES(R.string.tab_my_messages),
    AGENT_MESSAGES(R.string.tab_agent_messages),
    ALL_MESSAGES(R.string.tab_all_messages),
}

@Composable
fun MessageSearchPanel(
    messages: List<Message>,
    onNavigateToMessage: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedTab = remember { mutableIntStateOf(0) }
    val searchQuery = remember { mutableStateOf("") }
    val tabs = SearchTab.entries.toList()
    val currentTab = tabs[selectedTab.intValue]

    val roleFilter: (Message) -> Boolean = when (currentTab) {
        SearchTab.MY_MESSAGES -> { m: Message -> m.role == MessageRole.USER }
        SearchTab.AGENT_MESSAGES -> { m: Message -> m.role == MessageRole.ASSISTANT }
        SearchTab.ALL_MESSAGES -> { m: Message -> true }
    }

    val filteredMessages = remember(messages, selectedTab.intValue, searchQuery.value) {
        val roleFiltered = messages.filter(roleFilter)
        if (searchQuery.value.isBlank()) {
            when (currentTab) {
                SearchTab.MY_MESSAGES -> roleFiltered.reversed()
                SearchTab.AGENT_MESSAGES -> emptyList()
                SearchTab.ALL_MESSAGES -> emptyList()
            }
        } else {
            val query = searchQuery.value.lowercase()
            roleFiltered.filter { msg ->
                messageSummary(msg, 300).lowercase().contains(query)
                    || msg.parts.any {
                        it is Part.ToolInvocationPart && it.toolName.lowercase().contains(query)
                    }
            }.reversed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = searchQuery.value,
                onValueChange = { searchQuery.value = it },
                label = { Text(stringResource(R.string.search_messages)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
            }
        }

        TabRow(selectedTabIndex = selectedTab.intValue) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab.intValue == index,
                    onClick = { selectedTab.intValue = index },
                    text = { Text(stringResource(tab.labelResId)) },
                )
            }
        }

        if (filteredMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (searchQuery.value.isBlank() && currentTab != SearchTab.MY_MESSAGES)
                        stringResource(R.string.enter_search_query)
                    else
                        stringResource(R.string.no_search_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filteredMessages, key = { it.id }) { message ->
                    val messageIndex = messages.indexOf(message)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                if (messageIndex >= 0) {
                                    onNavigateToMessage(messageIndex)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = message.createdAt.toDateTimeString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (message.role == MessageRole.USER) stringResource(R.string.label_you) else stringResource(R.string.label_agent),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (message.role == MessageRole.USER) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = messageSummary(message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}