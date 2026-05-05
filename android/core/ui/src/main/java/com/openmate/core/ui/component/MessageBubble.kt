package com.openmate.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
) {
    if (isUser) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
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
                isTextSelectable = true,
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
            isTextSelectable = true,
        )
    }
}
