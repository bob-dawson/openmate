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
import com.openmate.core.common.toDateTimeString
import com.openmate.core.domain.model.SessionMessage
import com.openmate.feature.session.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun sessionMessageSummary(message: SessionMessage, maxLen: Int = 60): String {
    val data = runCatching { Json.parseToJsonElement(message.data).jsonObject }.getOrNull() ?: return "—"
    val text = data["text"]?.jsonPrimitive?.contentOrNull
    if (text != null && text.isNotBlank()) {
        return if (text.length <= maxLen) text else "${text.take(maxLen)}…"
    }
    val content = data["content"]?.jsonArray
    if (content != null) {
        for (item in content) {
            val obj = item.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val t = obj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (t.isNotBlank()) return if (t.length <= maxLen) t else "${t.take(maxLen)}…"
                }
                "tool" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                    return name
                }
            }
        }
    }
    return "—"
}

@Composable
fun SessionMessageSearchPanel(
    messages: List<SessionMessage>,
    onNavigateToMessage: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedTab = remember { mutableIntStateOf(0) }
    val searchQuery = remember { mutableStateOf("") }
    val tabs = listOf(
        stringResource(R.string.tab_my_messages) to 0,
        stringResource(R.string.tab_agent_messages) to 1,
        stringResource(R.string.tab_all_messages) to 2,
    )
    val currentTabIndex = selectedTab.intValue

    val filteredMessages = remember(messages, currentTabIndex, searchQuery.value) {
        val roleFiltered = when (currentTabIndex) {
            0 -> messages.filter { it.type == "user" }
            1 -> messages.filter { it.type == "assistant" }
            else -> messages
        }
        if (searchQuery.value.isBlank()) {
            when (currentTabIndex) {
                0 -> roleFiltered.reversed()
                1 -> emptyList()
                else -> emptyList()
            }
        } else {
            val query = searchQuery.value.lowercase()
            roleFiltered.filter { msg ->
                sessionMessageSummary(msg, 300).lowercase().contains(query)
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

        TabRow(selectedTabIndex = currentTabIndex) {
            tabs.forEachIndexed { index, (label, _) ->
                Tab(
                    selected = currentTabIndex == index,
                    onClick = { selectedTab.intValue = index },
                    text = { Text(label) },
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
                    text = if (searchQuery.value.isBlank() && currentTabIndex != 0)
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
                            text = message.timeCreated.toDateTimeString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (message.type == "user") stringResource(R.string.label_you) else stringResource(R.string.label_agent),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (message.type == "user") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = sessionMessageSummary(message),
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
