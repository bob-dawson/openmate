package com.openmate.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText

private val CodeBlockBackground = Color(0xFF1e1e2e)
private val CodeBlockText = Color(0xFFcdd6f4)

@Composable
fun MessageBubble(
    text: String,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isUser) {
        MarkdownText(
            markdown = text,
            modifier = modifier
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            syntaxHighlightColor = CodeBlockBackground,
            syntaxHighlightTextColor = CodeBlockText,
        )
    } else {
        MarkdownText(
            markdown = text,
            modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            style = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            syntaxHighlightColor = CodeBlockBackground,
            syntaxHighlightTextColor = CodeBlockText,
        )
    }
}
