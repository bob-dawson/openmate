package com.openmate.feature.instance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.feature.instance.R
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
    val uiState by viewModel.uiState.collectAsState()
    val pin by viewModel.pin.collectAsState()

    if (editProfileId != null) {
        LaunchedEffect(editProfileId) {
            viewModel.loadProfileForEdit(editProfileId)
        }
    }

    Scaffold(
        topBar = {
            TopBar(title = stringResource(if (editProfileId != null) R.string.edit_instance else R.string.add_instance), onBack = onBack)
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
                onValueChange = { viewModel.name.value = it; viewModel.dismissError() },
                label = { Text(stringResource(R.string.instance_name)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = uiState !is AddInstanceUiState.Saving && uiState !is AddInstanceUiState.Pairing && uiState !is AddInstanceUiState.ConfirmingPairing,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { viewModel.address.value = it; viewModel.dismissError() },
                label = { Text(stringResource(R.string.instance_address)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = uiState !is AddInstanceUiState.Saving && uiState !is AddInstanceUiState.Pairing && uiState !is AddInstanceUiState.ConfirmingPairing,
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { viewModel.port.value = it; viewModel.dismissError() },
                label = { Text(stringResource(R.string.instance_port)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = uiState !is AddInstanceUiState.Saving && uiState !is AddInstanceUiState.Pairing && uiState !is AddInstanceUiState.ConfirmingPairing,
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(
                onClick = { viewModel.testConnection() },
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState !is AddInstanceUiState.Testing && uiState !is AddInstanceUiState.Saving && uiState !is AddInstanceUiState.Pairing && uiState !is AddInstanceUiState.ConfirmingPairing,
            ) {
                Text(stringResource(R.string.test_connection))
            }
            Spacer(modifier = Modifier.height(8.dp))
            when (uiState) {
                is AddInstanceUiState.Testing -> {
                    Text(
                        stringResource(R.string.testing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is AddInstanceUiState.TestSuccess -> {
                    val status = (uiState as AddInstanceUiState.TestSuccess).status
                    Column {
                        Text(
                            stringResource(R.string.bridge_connected, status.bridge.version),
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            stringResource(R.string.opencode_status, status.opencode.status),
                            color = when (status.opencode.status) {
                                "running" -> MaterialTheme.colorScheme.primary
                                "crashed" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (status.opencode.directory.isNotBlank()) {
                            Text(
                                stringResource(R.string.opencode_dir, status.opencode.directory),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                is AddInstanceUiState.Error -> {
                    Text(
                        (uiState as AddInstanceUiState.Error).message,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {}
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { viewModel.save(onBack) },
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank() && address.isNotBlank() && uiState !is AddInstanceUiState.Saving && uiState !is AddInstanceUiState.Pairing && uiState !is AddInstanceUiState.ConfirmingPairing,
            ) {
                if (uiState is AddInstanceUiState.Saving || uiState is AddInstanceUiState.Pairing || uiState is AddInstanceUiState.ConfirmingPairing) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                } else {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }

    if (uiState is AddInstanceUiState.Pairing || (pin != null && uiState !is AddInstanceUiState.Error)) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelPairing() },
            title = { Text("Pairing Required") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Enter this PIN in the Bridge CLI to approve:")
                    Spacer(modifier = Modifier.height(16.dp))
                    if (pin != null) {
                        Text(
                            pin!!,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Run: opencode-bridge approve <PIN>",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirmPairing() },
                    enabled = pin != null && uiState !is AddInstanceUiState.ConfirmingPairing,
                ) {
                    if (uiState is AddInstanceUiState.ConfirmingPairing) {
                        CircularProgressIndicator(modifier = Modifier.height(16.dp))
                    } else {
                        Text("Confirm")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelPairing() }) {
                    Text("Cancel")
                }
            },
        )
    }
}
