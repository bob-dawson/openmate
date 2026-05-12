package com.openmate.feature.session.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.openmate.feature.session.R
import androidx.compose.ui.unit.dp
import com.openmate.core.network.dto.ModelInfoDto
import com.openmate.core.network.dto.ProviderInfoDto
import com.openmate.core.network.dto.ProviderListDto

data class SelectedModel(
    val providerID: String,
    val modelID: String,
    val modelName: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: ProviderListDto?,
    currentModel: SelectedModel?,
    recentModels: List<SelectedModel>,
    onSelect: (SelectedModel) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(560.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.select_model),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.refresh),
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.search_models)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                singleLine = true,
            )

            if (currentModel != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.current_model),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${currentModel.providerID}/${currentModel.modelName}",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            val filteredProviders = filterProviders(providers, searchQuery)

            if (providers == null) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredProviders.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.no_models_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {

            LazyColumn(
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (searchQuery.isBlank() && recentModels.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(R.string.recent))
                    }
                    items(recentModels) { model ->
                        ModelRow(
                            modelID = model.modelID,
                            modelName = model.modelName,
                            providerName = model.providerID,
                            isSelected = currentModel?.let { it.providerID == model.providerID && it.modelID == model.modelID } == true,
                            onClick = { onSelect(model) },
                        )
                    }
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }

                filteredProviders.forEach { provider ->
                    val filteredModels = if (searchQuery.isBlank()) {
                        provider.models.values.toList()
                    } else {
                        provider.models.values.filter { matchesModel(it, searchQuery) }
                    }
                    if (filteredModels.isNotEmpty()) {
                        item(provider.id) {
                            SectionHeader(provider.name.ifBlank { provider.id })
                        }
                        items(filteredModels, key = { "${provider.id}/${it.id}" }) { model ->
                            ModelRow(
                                modelID = model.id,
                                modelName = model.name.ifBlank { model.id },
                                providerName = provider.name.ifBlank { provider.id },
                                isSelected = currentModel?.let { it.providerID == provider.id && it.modelID == model.id } == true,
                                onClick = {
                                    onSelect(SelectedModel(
                                        providerID = provider.id,
                                        modelID = model.id,
                                        modelName = model.name.ifBlank { model.id },
                                    ))
                                },
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
private fun ModelRow(
    modelID: String,
    modelName: String,
    providerName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = modelName,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$providerName/$modelID",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun filterProviders(providers: ProviderListDto?, query: String): List<ProviderInfoDto> {
    if (providers == null) return emptyList()
    val connected = providers.connected.toSet()
    return providers.all
        .filter { connected.isEmpty() || connected.contains(it.id) }
        .filter { provider ->
            if (query.isBlank()) true
            else provider.name.contains(query, ignoreCase = true) ||
                    provider.id.contains(query, ignoreCase = true) ||
                    provider.models.values.any { matchesModel(it, query) }
        }
}

private fun matchesModel(model: ModelInfoDto, query: String): Boolean {
    return model.name.contains(query, ignoreCase = true) ||
            model.id.contains(query, ignoreCase = true) ||
            (model.family?.contains(query, ignoreCase = true) == true)
}
