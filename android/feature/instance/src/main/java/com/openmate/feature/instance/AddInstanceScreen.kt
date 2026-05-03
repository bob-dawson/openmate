package com.openmate.feature.instance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.ui.component.TopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddInstanceScreen(
    onBack: () -> Unit,
    editProfileId: String? = null,
    viewModel: AddInstanceViewModel = hiltViewModel(),
) {
    val name by viewModel.name.collectAsState()
    val address by viewModel.address.collectAsState()
    val port by viewModel.port.collectAsState()
    val password by viewModel.password.collectAsState()
    val testResult by viewModel.testResult.collectAsState()

    if (editProfileId != null) {
        LaunchedEffect(editProfileId) {
            viewModel.loadProfileForEdit(editProfileId)
        }
    }

    Scaffold(
        topBar = {
            TopBar(title = if (editProfileId != null) "编辑实例" else "Add Instance", onBack = onBack)
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { viewModel.name.value = it; viewModel.clearTestResult() },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { viewModel.address.value = it; viewModel.clearTestResult() },
                label = { Text("Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { viewModel.port.value = it; viewModel.clearTestResult() },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { viewModel.password.value = it },
                label = { Text("Password (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { viewModel.testConnection() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test Connection")
            }
            Spacer(modifier = Modifier.height(8.dp))
            when (testResult) {
                is TestResult.Testing -> Text("Testing...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                is TestResult.Success -> {
                    val status = (testResult as TestResult.Success).status
                    Column {
                        Text(
                            "Bridge v${status.bridge.version} connected!",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "OpenCode: ${status.opencode.status}",
                            color = when (status.opencode.status) {
                                "running" -> MaterialTheme.colorScheme.primary
                                "crashed" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (status.opencode.directory.isNotBlank()) {
                            Text(
                                "Dir: ${status.opencode.directory}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is TestResult.Error -> Text(
                    (testResult as TestResult.Error).message,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> {}
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.save(onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && address.isNotBlank(),
            ) {
                Text("Save")
            }
        }
    }
}
