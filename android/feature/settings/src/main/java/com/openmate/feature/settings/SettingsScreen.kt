package com.openmate.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.openmate.feature.settings.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.ui.component.TopBar
import com.openmate.core.ui.theme.Success

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val activeProfile by viewModel.activeProfile.collectAsState()
    val notifyPermissions by viewModel.notifyPermissions.collectAsState()
    val notifyQuestions by viewModel.notifyQuestions.collectAsState()
    val notifyComplete by viewModel.notifyComplete.collectAsState()
    val notifyErrors by viewModel.notifyErrors.collectAsState()
    val autoAllowRead by viewModel.autoAllowRead.collectAsState()
    val autoAllowGrep by viewModel.autoAllowGrep.collectAsState()
    val autoAllowBash by viewModel.autoAllowBash.collectAsState()

    Scaffold(
        topBar = {
            TopBar(title = stringResource(R.string.settings))
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                ProfileSection(
                    name = activeProfile?.name ?: stringResource(R.string.not_connected),
                    address = activeProfile?.let { "${it.address}:${it.port}" } ?: "",
                    onDisconnect = {
                        viewModel.disconnect()
                        onBack()
                    },
                )
            }

            item {
                SectionHeader(title = stringResource(R.string.notifications))
                SettingsCard {
                    SettingsToggle(
                        title = stringResource(R.string.notify_permissions),
                        subtitle = stringResource(R.string.notify_permissions_desc),
                        checked = notifyPermissions,
                        onCheckedChange = { viewModel.setNotifyPermissions(it) },
                    )
                    SettingsToggle(
                        title = stringResource(R.string.notify_questions),
                        subtitle = stringResource(R.string.notify_questions_desc),
                        checked = notifyQuestions,
                        onCheckedChange = { viewModel.setNotifyQuestions(it) },
                    )
                    SettingsToggle(
                        title = stringResource(R.string.notify_complete),
                        subtitle = stringResource(R.string.notify_complete_desc),
                        checked = notifyComplete,
                        onCheckedChange = { viewModel.setNotifyComplete(it) },
                    )
                    SettingsToggle(
                        title = stringResource(R.string.notify_errors),
                        subtitle = stringResource(R.string.notify_errors_desc),
                        checked = notifyErrors,
                        onCheckedChange = { viewModel.setNotifyErrors(it) },
                        showDivider = false,
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.auto_allow_rules))
                SettingsCard {
                    SettingsToggle(
                        title = stringResource(R.string.auto_allow_read),
                        subtitle = null,
                        checked = autoAllowRead,
                        onCheckedChange = { viewModel.setAutoAllowRead(it) },
                    )
                    SettingsToggle(
                        title = stringResource(R.string.auto_allow_grep),
                        subtitle = null,
                        checked = autoAllowGrep,
                        onCheckedChange = { viewModel.setAutoAllowGrep(it) },
                    )
                    SettingsToggle(
                        title = stringResource(R.string.auto_allow_bash),
                        subtitle = stringResource(R.string.security_risk),
                        subtitleColor = Color(0xFFf5a742),
                        checked = autoAllowBash,
                        onCheckedChange = { viewModel.setAutoAllowBash(it) },
                        showDivider = false,
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.cache_storage))
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.file_cache),
                        subtitle = viewModel.cacheSize.collectAsState().value,
                        trailing = {
                            Text(
                                text = stringResource(R.string.clear),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.clickable { viewModel.clearCache() },
                            )
                        },
                    )
                    SettingsRow(
                        title = stringResource(R.string.cache_policy),
                        subtitle = viewModel.cachePolicyLabel.collectAsState().value,
                        showDivider = false,
                        trailing = {
                            Text(
                                text = "›",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.about))
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.version),
                        subtitle = null,
                        trailing = {
                            Text(
                                text = "1.0.0",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    SettingsRow(
                        title = stringResource(R.string.open_source_licenses),
                        subtitle = null,
                        showDivider = false,
                        trailing = {
                            Text(
                                text = "›",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ProfileSection(
    name: String,
    address: String,
    onDisconnect: () -> Unit,
) {
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (address.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = address,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = stringResource(R.string.disconnect),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onDisconnect),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraSmall)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.extraSmall),
    ) {
        content()
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    subtitle: String?,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = subtitleColor,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Success.copy(alpha = 0.3f),
                    checkedBorderColor = Success,
                    checkedThumbColor = Success,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedThumbColor = Color(0xFF808080),
                ),
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String?,
    showDivider: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
