package com.almus.appcreatorpro.ui.artifacts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.service.artifacts.ArtifactEntry
import com.almus.appcreatorpro.service.artifacts.ArtifactFilter
import com.almus.appcreatorpro.service.artifacts.ArtifactOrganizer
import com.almus.appcreatorpro.service.artifacts.ArtifactSort
import com.almus.appcreatorpro.service.artifacts.ArtifactType
import com.almus.appcreatorpro.service.artifacts.BuildKind
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.builds.ApkActions
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.util.Formatters

/** Artifact manager: every APK and AAB saved on this phone, checked and organized. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(
    onBack: () -> Unit,
    onOpenBuild: (String) -> Unit,
    viewModel: ArtifactsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadStates.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var exporting by remember { mutableStateOf<ArtifactEntry?>(null) }
    var toDelete by remember { mutableStateOf<ArtifactEntry?>(null) }
    var cleaning by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(1) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ApkActions.GENERIC_MIME)) { uri ->
        val item = exporting
        exporting = null
        if (uri != null && item != null) viewModel.export(item) { context.contentResolver.openOutputStream(uri) }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    toDelete?.let { item ->
        val release = item.kindHint == BuildKind.RELEASE
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(if (release) "Delete this RELEASE file?" else "Delete this file?") },
            text = { Text(ArtifactTexts.deleteWarning(item)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(item); toDelete = null }) {
                    Text("Delete", color = if (release) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }

    val all = (state as? UiState.Success)?.data.orEmpty()
    if (cleaning) {
        val plan = ArtifactOrganizer.cleanupPlan(all, keep)
        AlertDialog(
            onDismissRequest = { cleaning = false },
            title = { Text("Clean up older files") },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BodyText("Keeps the newest files of every project, file type and debug/release kind, and deletes the older ones.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, 3).forEach { n ->
                            FilterChip(selected = keep == n, onClick = { keep = n }, label = { Text("Keep $n") })
                        }
                    }
                    if (plan.delete.isEmpty()) {
                        BodyText("Nothing to delete.")
                    } else {
                        BodyText("${plan.delete.size} file(s) will be deleted, freeing ${Formatters.formatBytes(plan.bytes)}:")
                        plan.delete.take(8).forEach { BodyText("- ${it.fileName} (${it.projectName})") }
                        if (plan.delete.size > 8) BodyText("...and ${plan.delete.size - 8} more")
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = plan.delete.isNotEmpty(), onClick = { viewModel.cleanUp(plan); cleaning = false }) { Text("Delete them") }
            },
            dismissButton = { TextButton(onClick = { cleaning = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Artifacts") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { viewModel.load(recheck = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "Check all files again") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                UiState.Loading -> LoadingView(message = "Looking for saved files...")
                is UiState.Error -> ErrorView(message = s.message, onRetry = { viewModel.load() })
                is UiState.Success -> {
                    val groups = remember(s.data, filter, sort) { ArtifactOrganizer.arrange(s.data, filter, sort) }
                    if (s.data.isEmpty() && pending.isEmpty()) {
                        EmptyView(
                            icon = Icons.AutoMirrored.Filled.List,
                            title = "No saved files",
                            message = "APK and AAB files you download from a finished build are listed here.",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item(key = "controls") {
                                Controls(
                                    filter = filter,
                                    sort = sort,
                                    total = s.data.size,
                                    totalBytes = s.data.sumOf { it.sizeBytes },
                                    onFilter = viewModel::setFilter,
                                    onSort = viewModel::setSort,
                                    onCleanUp = { cleaning = true },
                                )
                            }
                            if (pending.isNotEmpty()) {
                                item(key = "pending-title") { Text("On GitHub, not saved on this phone", style = MaterialTheme.typography.titleMedium) }
                                items(pending, key = { "pending-" + it.record.id + "-" + it.artifact.id }) { p ->
                                    val st = downloads[DownloadKey(p.record.id, p.artifact.id)]
                                    SectionCard(p.artifact.name, badge = if (p.artifact.isBundle) "AAB" else "APK") {
                                        BodyText("${p.record.projectName} - ${Formatters.formatBytes(p.artifact.sizeBytes)}")
                                        when (st) {
                                            is DownloadState.Active -> {
                                                BodyText("Downloading and checking...")
                                                OutlinedButton(onClick = { viewModel.cancelDownload(p) }) { Text("Cancel") }
                                            }
                                            is DownloadState.Failed -> {
                                                Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                                                if (st.retryable) Button(onClick = { viewModel.download(p) }) { Text("Try again") }
                                            }
                                            else -> Button(onClick = { viewModel.download(p) }) { Text("Download") }
                                        }
                                    }
                                }
                            }
                            if (s.data.isNotEmpty() && groups.isEmpty()) {
                                item(key = "nomatch") { BodyText("No saved file matches these filters.") }
                            }
                            groups.forEach { g ->
                                item(key = "group-" + g.projectName) {
                                    Text(
                                        "${g.projectName} - ${g.items.size} file(s), ${Formatters.formatBytes(g.totalBytes)}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                items(g.items, key = { it.key }) { item ->
                                    ArtifactCard(
                                        item = item,
                                        onShare = {
                                            val blocked = item.shareBlockedReason
                                            val error = blocked ?: ApkActions.share(context, item.file, ArtifactTexts.badge(item))
                                            if (error != null) viewModel.showMessage(error)
                                        },
                                        onExport = {
                                            val blocked = item.shareBlockedReason
                                            if (blocked != null) {
                                                viewModel.showMessage(blocked)
                                            } else {
                                                exporting = item
                                                saveLauncher.launch(item.file.fileName)
                                            }
                                        },
                                        onVerify = { viewModel.verify(item) },
                                        onDelete = { toDelete = item },
                                        onOpenBuild = { onOpenBuild(item.buildId) },
                                        onMessage = viewModel::showMessage,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Controls(
    filter: ArtifactFilter,
    sort: ArtifactSort,
    total: Int,
    totalBytes: Long,
    onFilter: (ArtifactFilter) -> Unit,
    onSort: (ArtifactSort) -> Unit,
    onCleanUp: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyText("$total saved file(s), ${Formatters.formatBytes(totalBytes)} on this phone")
        OutlinedTextField(
            value = filter.query,
            onValueChange = { onFilter(filter.copy(query = it)) },
            label = { Text("Search project, package, version or name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter.type == null, onClick = { onFilter(filter.copy(type = null)) }, label = { Text("All types") })
            FilterChip(selected = filter.type == ArtifactType.APK, onClick = { onFilter(filter.copy(type = ArtifactType.APK)) }, label = { Text("APK") })
            FilterChip(selected = filter.type == ArtifactType.AAB, onClick = { onFilter(filter.copy(type = ArtifactType.AAB)) }, label = { Text("AAB") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = filter.kind == null, onClick = { onFilter(filter.copy(kind = null)) }, label = { Text("Debug + release") })
            FilterChip(selected = filter.kind == BuildKind.DEBUG, onClick = { onFilter(filter.copy(kind = BuildKind.DEBUG)) }, label = { Text("Debug") })
            FilterChip(selected = filter.kind == BuildKind.RELEASE, onClick = { onFilter(filter.copy(kind = BuildKind.RELEASE)) }, label = { Text("Release") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArtifactSort.entries.forEach { o ->
                FilterChip(selected = sort == o, onClick = { onSort(o) }, label = { Text(o.label) })
            }
        }
        if (total > 1) OutlinedButton(onClick = onCleanUp) { Text("Clean up older files...") }
    }
}
