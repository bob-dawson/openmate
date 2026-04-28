package com.openmate.feature.instance

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.openmate.core.domain.model.ConnectionStatus
import com.openmate.core.ui.component.EmptyStateView
import com.openmate.core.ui.component.TopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceListScreen(
    onNavigateToAdd: () -> Unit,
    onNavigateToSessions: () -> Unit,
    viewModel: InstanceListViewModel = hiltViewModel(),
) {
    val profiles by viewModel.profiles.collectAsState()

    Scaffold(
        topBar = {
            TopBar(title = "OpenMate")
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add Instance")
            }
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            EmptyStateView(
                message = "Add your first opencode instance",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(profiles, key = { it.profile.id }) { item ->
                    ProfileCard(
                        profileWithStatus = item,
                        onClick = {
                            viewModel.connect(item.profile)
                            onNavigateToSessions()
                        },
                        onDelete = { viewModel.deleteProfile(item.profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profileWithStatus: ProfileWithStatus,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val (profile, status) = profileWithStatus
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .clip(CircleShape),
            ) {
                val color = when (status) {
                    ConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
                    ConnectionStatus.CONNECTING -> Color(0xFFFFC107)
                    ConnectionStatus.ERROR -> Color(0xFFF44336)
                    ConnectionStatus.DISCONNECTED -> Color(0xFF9E9E9E)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${profile.address}:${profile.port}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
