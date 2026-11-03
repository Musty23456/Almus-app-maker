package com.almus.appcreatorpro.ui.artifacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.builds.ApkActions
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.util.Formatters

/** Artifact manager: every APK and AAB saved on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(
    onBack: () -> Unit,
    onOpenBuild: (String) -> Unit,
    viewModel: ArtifactsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var pending by remember { mutableStateOf<ManagedArtifact?>(null) }
    var toDelete by remember { mutableStateOf<ManagedArtifact?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ApkActions.GENERIC_MIME)) { uri ->
        val item = pending
        pending = null
        if (uri != null && item != null) viewModel.export(item) { context.contentResolver.openOutputStream(uri) }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    toDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this file?") },
            text = { Text("${item.file.fileName} will be removed from this phone. You can download it again from GitHub while it has not expired.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(item); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Artifacts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> LoadingView(message = "Looking for saved files...")
                is UiState.Error -> ErrorView(message = s.message, onRetry = viewModel::load)
                is UiState.Success -> if (s.data.isEmpty()) {
                    EmptyView(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = "No saved files",
                        message = "APK and AAB files you download from a finished build are listed here.",
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(s.data, key = { it.buildId + "/" + it.file.fileName }) { item ->
                            SectionCard(item.file.fileName, badge = if (item.file.isBundle) "AAB" else "APK") {
                                BodyText("${item.projectName} - ${item.buildLabel}")
                                BodyText("Size: ${Formatters.formatBytes(item.file.sizeBytes)}")
                                val d = item.details
                                val origin = if (d.fromFile) "" else " (from the build settings)"
                                BodyText("Package: ${d.packageName ?: "not available"}$origin")
                                BodyText(
                                    "Version: ${d.versionName ?: "not available"}" +
                                        (d.versionCode?.let { " ($it)" } ?: ""),
                                )
                                BodyText("Saved: ${Formatters.formatDateTime(item.savedAtMillis)}")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { ApkActions.share(context, item.file)?.let(viewModel::showMessage) }) { Text("Share") }
                                    OutlinedButton(onClick = { pending = item; saveLauncher.launch(item.file.fileName) }) { Text("Save to...") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onOpenBuild(item.buildId) }) { Text("Open build") }
                                    TextButton(onClick = { toDelete = item }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
