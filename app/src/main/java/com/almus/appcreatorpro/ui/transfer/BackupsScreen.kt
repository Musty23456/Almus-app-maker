package com.almus.appcreatorpro.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupsScreen(
    projectId: String?,
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    viewModel: BackupsViewModel = viewModel(factory = AppViewModelProvider.backupsFactory(projectId)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var restoreTarget by remember { mutableStateOf<BackupItem?>(null) }
    var deleteTarget by remember { mutableStateOf<BackupItem?>(null) }
    var replaceTarget by remember { mutableStateOf<BackupItem?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.projectName?.let { "Backups: $it" } ?: "Backups") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.canCreate) {
                OutlinedButton(
                    onClick = viewModel::createBackup,
                    enabled = state.busyTitle == null,
                    modifier = Modifier.padding(16.dp),
                ) { Text("Back up now") }
            }
            if (state.loading) {
                LoadingView(message = "Loading backups...")
            } else if (state.items.isEmpty()) {
                EmptyView(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "No backups yet",
                    message = "Backups are saved on this device and kept even if the project is deleted.",
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.items, key = { it.info.id }) { item ->
                        BackupRow(
                            item = item,
                            showProjectName = projectId == null,
                            onRestore = { restoreTarget = item },
                            onDelete = { deleteTarget = item },
                        )
                    }
                }
            }
        }
    }

    state.busyTitle?.let { TransferProgressDialog(title = it, progress = state.progress, onCancel = null) }

    restoreTarget?.let { item ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore backup") },
            text = {
                Text(
                    "Restore as a new project keeps everything you have now. " +
                        if (item.projectExists) "Replacing overwrites the current project's files (a safety backup is made first)."
                        else "The original project no longer exists, so it can only be restored as a new project.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    TextButton(onClick = { restoreTarget = null; viewModel.restoreAsNew(item) }) { Text("Restore as new project") }
                    if (item.projectExists) {
                        TextButton(onClick = { restoreTarget = null; replaceTarget = item }) {
                            Text("Replace current project", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { restoreTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    replaceTarget?.let { item ->
        ConfirmDialog(
            title = "Replace current project?",
            message = "\"${item.info.projectName}\" will get the files from this backup. All changes made since " +
                "${Formatters.formatDateTime(item.info.createdAtMillis)} will be lost, but a safety backup of the current state is saved first.",
            confirmLabel = "Replace",
            destructive = true,
            onConfirm = { replaceTarget = null; viewModel.restoreReplacing(item) },
            onDismiss = { replaceTarget = null },
        )
    }

    deleteTarget?.let { item ->
        ConfirmDialog(
            title = "Delete backup?",
            message = "This backup (${Formatters.formatDateTime(item.info.createdAtMillis)}) will be permanently deleted.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { deleteTarget = null; viewModel.delete(item) },
            onDismiss = { deleteTarget = null },
        )
    }

    val restored = state.restored
    if (restored != null) {
        ConfirmDialog(
            title = "Backup restored",
            message = "\"${restored.name}\" was created from the backup. Open it now?",
            confirmLabel = "Open",
            onConfirm = { viewModel.consumeRestored(); onOpenProject(restored.id) },
            onDismiss = { viewModel.consumeRestored() },
        )
    }
}

@Composable
private fun BackupRow(item: BackupItem, showProjectName: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val info = item.info
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = if (showProjectName) "${info.projectName} - ${info.label}" else info.label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "${Formatters.formatDateTime(info.createdAtMillis)}  |  ${info.fileCount} files  |  ${Formatters.formatBytes(info.zipBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!item.projectExists) {
                Text("Original project was deleted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
