package com.openmate.feature.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.openmate.feature.session.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.openmate.core.domain.model.QuestionInfo
import com.openmate.core.domain.model.QuestionOption
import com.openmate.core.domain.model.QuestionRequest

@Composable
fun QuestionDialog(
    request: QuestionRequest,
    onSubmit: (List<List<String>>) -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.question),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.height(8.dp))

                val selectedAnswers = remember { mutableStateOf<Map<Int, List<String>>>(emptyMap()) }
                val customAnswers = remember { mutableStateOf<Map<Int, String>>(emptyMap()) }

                request.questions.forEachIndexed { qIndex, questionInfo ->
                    Text(
                        text = questionInfo.header,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = questionInfo.question,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Column(Modifier.selectableGroup()) {
                        questionInfo.options.forEach { option ->
                            val selected = option.label in (selectedAnswers.value[qIndex] ?: emptyList())
                            OutlinedButton(
                                onClick = {
                                    selectedAnswers.value = selectedAnswers.value.toMutableMap().apply {
                                        val current = this[qIndex] ?: emptyList()
                                        this[qIndex] = if (questionInfo.multiple) {
                                            if (option.label in current) current - option.label else current + option.label
                                        } else {
                                            listOf(option.label)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .selectable(
                                        selected = selected,
                                        onClick = {},
                                        role = Role.RadioButton,
                                    ),
                            ) {
                                Text(
                                    text = if (selected) "\u2713 ${option.label}" else option.label,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }
                        if (questionInfo.custom) {
                            val customValue = customAnswers.value[qIndex].orEmpty()
                            OutlinedTextField(
                                value = customValue,
                                onValueChange = { value ->
                                    val previous = customAnswers.value[qIndex].orEmpty()
                                    customAnswers.value = customAnswers.value.toMutableMap().apply {
                                        this[qIndex] = value
                                    }
                                    val trimmed = value.trim()
                                    selectedAnswers.value = selectedAnswers.value.toMutableMap().apply {
                                        val existing = (this[qIndex] ?: emptyList()).toMutableList().apply {
                                            if (previous.isNotBlank()) remove(previous)
                                        }
                                        when {
                                            trimmed.isBlank() && existing.isEmpty() -> remove(qIndex)
                                            trimmed.isBlank() -> this[qIndex] = existing
                                            questionInfo.multiple -> this[qIndex] = (existing + trimmed).distinct()
                                            else -> this[qIndex] = listOf(trimmed)
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                singleLine = !questionInfo.multiple,
                                label = { Text(stringResource(R.string.question_custom_answer)) },
                                placeholder = { Text(stringResource(R.string.question_custom_answer_placeholder)) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.align(Alignment.End)) {
                    OutlinedButton(onClick = onReject) {
                        Text(stringResource(R.string.reject))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onSubmit(request.questions.indices.map { index -> selectedAnswers.value[index].orEmpty() })
                        },
                        enabled = selectedAnswers.value.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.submit))
                    }
                }
            }
        }
    }
}
