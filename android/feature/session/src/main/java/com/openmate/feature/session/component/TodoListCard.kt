package com.openmate.feature.session.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.TodoInfo
import com.openmate.core.ui.theme.Success
import com.openmate.core.ui.theme.Warning

@Composable
fun TodoListCard(
    todos: List<TodoInfo>,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return

    val expanded = remember { mutableStateOf(true) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Tasks (${todos.count { it.status == "completed" }}/${todos.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = { expanded.value = !expanded.value }) {
                    Text(
                        text = if (expanded.value) "▲" else "▼",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            AnimatedVisibility(visible = expanded.value) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    todos.forEach { todo ->
                        TodoItemRow(todo)
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoItemRow(todo: TodoInfo) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (symbol, color) = when (todo.status) {
            "completed" -> "✓" to Success
            "in_progress" -> "•" to Warning
            "cancelled" -> "✗" to MaterialTheme.colorScheme.onSurfaceVariant
            else -> " " to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(
            text = "[$symbol]",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodySmall,
            color = if (todo.status == "completed") {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
