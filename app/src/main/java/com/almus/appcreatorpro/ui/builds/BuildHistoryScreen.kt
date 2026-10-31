package com.almus.appcreatorpro.ui.builds

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.components.BuildCard
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.PrototypeBadge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildHistoryScreen(
    onBack: () -> Unit,
    onOpenBuild: (String) -> Unit = {},
    viewModel: BuildHistoryViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val downloads by viewModel.downloadStates.collectAsStateWithLifecycle()
    val savedIds by viewModel.savedBuildIds.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Build history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    val anySample = (state as? UiState.Success)?.data?.any { it.isSample } == true
                    if (anySample) PrototypeBadge(label = "SAMPLE DATA", modifier = Modifier.padding(end = 12.dp))
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (val current = state) {
                UiState.Loading -> LoadingView(message = "Loading builds...")
                is UiState.Error -> ErrorView(message = current.message, onRetry = viewModel::load)
                is UiState.Success -> {
                    if (current.data.isEmpty()) {
                        EmptyView(
                            icon = Icons.AutoMirrored.Filled.List,
                            title = "No builds yet",
                            message = "Cloud builds you start (Projects > menu > Cloud build) are listed here with their real GitHub Actions status.",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        val now = remember { System.currentTimeMillis() }
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(current.data.size) { index ->
                                val build = current.data[index]
                                BuildCard(
                                    build = build,
                                    nowMillis = now,
                                    onClick = if (build.isSample) null else ({ onOpenBuild(build.id) }),
                                    extra = {
                                        if (!build.isSample) {
                                            // savedIds is a key so the line updates when a download finishes.
                                            val availability = remember(savedIds, build) { viewModel.availability(build) }
                                            HistoryApkLine(
                                                build = build,
                                                availability = availability,
                                                state = build.artifacts.firstOrNull { it.isApk }?.let { downloads[DownloadKey(build.id, it.id)] },
                                                onDownload = { viewModel.download(build) },
                                                onCancel = { viewModel.cancelDownload(build) },
                                                onOpen = { onOpenBuild(build.id) },
                                            )
                                        }
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

/** One line under a history card: is there an APK, and what can the user do with it. */
@Composable
private fun HistoryApkLine(
    build: BuildRecord,
    availability: ApkAvailability,
    state: DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        when (state) {
            is DownloadState.Active -> {
                val f = state.progress.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Downloading APK...", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
                return@Column
            }
            is DownloadState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            DownloadState.Cancelled -> Text("Download cancelled.", style = MaterialTheme.typography.bodySmall, color = muted)
            else -> Unit
        }
        when (availability) {
            ApkAvailability.ON_PHONE -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("APK saved on this phone", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpen) { Text("Open / share") }
            }
            ApkAvailability.DOWNLOADABLE -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("APK available on GitHub", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onDownload) { Text("Download APK") }
            }
            ApkAvailability.EXPIRED -> Text("APK expired on GitHub - build again for a new one.", style = MaterialTheme.typography.bodySmall, color = muted)
            ApkAvailability.DELETED -> Text("APK was deleted on GitHub - build again for a new one.", style = MaterialTheme.typography.bodySmall, color = muted)
            ApkAvailability.NONE -> if (build.status == BuildStatus.FAILED) {
                Text("Failed - open for the error and a retry button.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}
