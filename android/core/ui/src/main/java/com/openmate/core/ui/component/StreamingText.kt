package com.openmate.core.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

@Composable
fun StreamingText(
    fullText: String,
    modifier: Modifier = Modifier,
) {
    val displayedChars = remember { mutableStateOf(0) }

    LaunchedEffect(fullText) {
        if (displayedChars.value > fullText.length) {
            displayedChars.value = fullText.length
        }
        while (displayedChars.value < fullText.length) {
            displayedChars.value += 1
            delay(16)
        }
    }

    Text(
        text = fullText.substring(0, displayedChars.value.coerceAtMost(fullText.length)),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
