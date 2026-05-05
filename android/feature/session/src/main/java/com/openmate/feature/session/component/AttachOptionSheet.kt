package com.openmate.feature.session.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openmate.feature.session.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachOptionSheet(
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onServerFiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            AttachOptionRow(
                icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.from_gallery),
                onClick = {
                    onDismiss()
                    onGallery()
                },
            )
            AttachOptionRow(
                icon = { Icon(Icons.Default.InsertDriveFile, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.from_files),
                onClick = {
                    onDismiss()
                    onFiles()
                },
            )
            AttachOptionRow(
                icon = { Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(24.dp)) },
                label = stringResource(R.string.from_server),
                onClick = {
                    onDismiss()
                    onServerFiles()
                },
            )
        }
    }
}

@Composable
private fun AttachOptionRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
