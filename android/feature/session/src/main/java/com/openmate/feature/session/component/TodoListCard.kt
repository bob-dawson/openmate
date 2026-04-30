package com.openmate.feature.session.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.TodoInfo

@Composable
fun TodoListCard(
    todos: List<TodoInfo>,
    modifier: Modifier = Modifier,
) {
    if (todos.isEmpty()) return

    val expanded = remember { mutableStateOf(false) }

    val inProgress = todos.count { it.status == "in_progress" }
    val pending = todos.count { it.status == "pending" }
    val completed = todos.count { it.status == "completed" }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { expanded.value = !expanded.value },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TODO",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(6.dp))
            if (inProgress > 0) {
                StatusBadge(count = inProgress, label = "进行中", color = Info)
            }
            if (pending > 0) {
                StatusBadge(count = pending, label = "待办", color = Muted)
            }
            if (completed > 0 && expanded.value) {
                StatusBadge(count = completed, label = "已完成", color = Success)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (expanded.value) "收起" else "查看全部 ›",
                style = MaterialTheme.typography.labelSmall,
                color = if (expanded.value) Primary else Muted,
                fontWeight = if (expanded.value) FontWeight.Medium else FontWeight.Normal,
            )
        }

        val sortedTodos = if (expanded.value) {
            todos
        } else {
            val inProgressItems = todos.filter { it.status == "in_progress" }
            val pendingItems = todos.filter { it.status == "pending" }
            val rest = todos.filter { it.status != "in_progress" && it.status != "pending" }
            (inProgressItems + pendingItems + rest).take(3)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sortedTodos.forEach { todo ->
                TodoItemRow(todo, expanded.value)
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
    }
}

private val Info = androidx.compose.ui.graphics.Color(0xFF56B6C2)
private val Success = androidx.compose.ui.graphics.Color(0xFF7FD88F)
private val Primary = androidx.compose.ui.graphics.Color(0xFFFAB283)
private val Muted = androidx.compose.ui.graphics.Color(0xFF808080)
private val HighPriority = androidx.compose.ui.graphics.Color(0xFFE06C75)
private val MedPriority = androidx.compose.ui.graphics.Color(0xFFF5A742)
private val LowPriority = androidx.compose.ui.graphics.Color(0xFF808080)

@Composable
private fun StatusBadge(count: Int, label: String, color: androidx.compose.ui.graphics.Color) {
    Spacer(modifier = Modifier.width(4.dp))
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = "$count $label",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun TodoItemRow(todo: TodoInfo, expanded: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (todo.status) {
            "in_progress" -> {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .border(2.dp, Info, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Info),
                    )
                }
            }
            "completed" -> {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Success),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
                    )
                }
            }
            "cancelled" -> {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Muted),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✗",
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color(0xFF0A0A0A),
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .border(1.5.dp, Muted, RoundedCornerShape(3.dp)),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodySmall,
            color = when (todo.status) {
                "completed", "cancelled" -> Muted
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            textDecoration = if (todo.status == "completed" || todo.status == "cancelled") {
                TextDecoration.LineThrough
            } else null,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        PriorityBadge(priority = todo.priority)
    }
}

@Composable
private fun PriorityBadge(priority: String) {
    val (color, bgColor) = when (priority) {
        "high" -> HighPriority to HighPriority.copy(alpha = 0.15f)
        "medium" -> MedPriority to MedPriority.copy(alpha = 0.15f)
        else -> LowPriority to LowPriority.copy(alpha = 0.15f)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(2.dp))
            .background(bgColor)
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = when (priority) {
                "high" -> "高"
                "medium" -> "中"
                else -> "低"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
