package com.openmate.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText

private val CodeBlockBackground = Color(0xFF2a2a3a)
private val CodeBlockText = Color(0xFFe0e0f0)

@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
    isTextSelectable: Boolean = true,
) {
    if (isUser) {
        val primaryColor = MaterialTheme.colorScheme.primary
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .drawBehind {
                    drawRect(
                        color = primaryColor,
                        topLeft = Offset.Zero,
                        size = Size(3.dp.toPx(), size.height),
                    )
                },
        ) {
            MarkdownText(
                markdown = text,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                syntaxHighlightColor = CodeBlockBackground,
                syntaxHighlightTextColor = CodeBlockText,
                isTextSelectable = isTextSelectable,
                enableSoftBreakAddsNewLine = true,
            )
        }
    } else {
        MarkdownText(
            markdown = text,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            syntaxHighlightColor = CodeBlockBackground,
            syntaxHighlightTextColor = CodeBlockText,
            isTextSelectable = isTextSelectable,
            enableSoftBreakAddsNewLine = true,
        )
    }
}
