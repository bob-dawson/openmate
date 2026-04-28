package com.openmate.feature.session.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openmate.core.domain.model.Message
import com.openmate.core.domain.model.MessageRole

@Composable
fun MessageItem(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val alignment = if (isUser) Alignment.TopEnd else Alignment.TopStart

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp),
        contentAlignment = alignment,
    ) {
        PartColumn(
            parts = message.parts,
            isUser = isUser,
        )
    }
}
