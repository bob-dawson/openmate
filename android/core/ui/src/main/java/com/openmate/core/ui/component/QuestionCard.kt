package com.openmate.core.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun QuestionCard(
    question: String,
    options: List<String>,
    onSubmit: (List<String>) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedOptions = remember { mutableStateOf(setOf<String>()) }

    Card(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Question",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = question,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(Modifier.selectableGroup()) {
                options.forEach { option ->
                    val selected = option in selectedOptions.value
                    OutlinedButton(
                        onClick = {
                            selectedOptions.value = if (selected) {
                                selectedOptions.value - option
                            } else {
                                selectedOptions.value + option
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
                            text = if (selected) "\u2713 $option" else option,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onSubmit(selectedOptions.value.toList()) },
                enabled = selectedOptions.value.isNotEmpty(),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("Submit")
            }
        }
    }
}
