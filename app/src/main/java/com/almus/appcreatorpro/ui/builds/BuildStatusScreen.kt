package com.almus.appcreatorpro.ui.builds

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadProgress
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.service.artifacts.StoredApk
import com.almus.appcreatorpro.service.build.BuildDetails
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.BuildStatusPill
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.util.Formatters
import kotlinx.coroutines.delay

/**
 * Build monitor + results for one build: live status and timing, step summary, log and compiler errors,
 * APK artifacts with download / share / save, and retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildStatusScreen(
    buildId: String,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenGitHub: () -> Unit = {},
    onOpenBuild: (String) -> Unit = {},
    viewModel: BuildStatusViewModel = viewModel(
        key = "build-$buildId",
        factory = AppViewModelProvider.buildStatusFactory(buildId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val retry by viewModel.retry.collectAsStateWithLifecycle()
    val export by viewModel.export.collectAsStateWithLifecycle()

    // Coming back to the screen (for example after reconnecting GitHub) checks GitHub again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(retry) {
        when (val r = retry) {
            is RetryUi.Started -> {
                viewModel.clearRetry()
                onOpenBuild(r.newBuildId)
            }
            is RetryUi.Failed -> {
                snackbar.showSnackbar(r.message)
                viewModel.clearRetry()
            }
            else -> Unit
        }
    }
    LaunchedEffect(export) {
        when (val e = export) {
            is ExportUi.Done -> {
                snackbar.showSnackbar("Saved ${e.fileName}")
                viewModel.clearExport()
            }
            is ExportUi.Failed -> {
                snackbar.showSnackbar(e.message)
                viewModel.clearExport()
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Build status") },
                navigationIcon = {
                    IconButton(onClick = { onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = onOpenHistory) { Text("History") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                BuildStatusState.Loading -> LoadingView(message = "Loading build...")
                BuildStatusState.NotFound -> BodyText("This build is not in the history.", modifier = Modifier.padding(16.dp))
                is BuildStatusState.Found -> StatusContent(s.record, s.polling, viewModel, snackbar, onOpenGitHub)
            }
        }
    }
}

@Composable
private fun StatusContent(
    record: BuildRecord,
    polling: Boolean,
    vm: BuildStatusViewModel,
    snackbar: SnackbarHostState,
    onOpenGitHub: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val details by vm.details.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    val retry by vm.retry.collectAsStateWithLifecycle()
    val downloads by vm.downloadStates.collectAsStateWithLifecycle()
    val saved by vm.savedApks.collectAsStateWithLifecycle()
    val needsReconnect by vm.needsReconnect.collectAsStateWithLifecycle()

    // A ticking clock only while the build is unfinished, so the duration counts up live.
    val nowMillis by produceState(System.currentTimeMillis(), record.status.isTerminal) {
        while (!record.status.isTerminal) {
            value = System.currentTimeMillis()
            delay(1000)
        }
        value = System.currentTimeMillis()
    }

    var pendingExport by remember { mutableStateOf<StoredApk?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ApkActions.APK_MIME)) { uri ->
        val apk = pendingExport
        pendingExport = null
        if (uri != null && apk != null) vm.exportApk(apk) { context.contentResolver.openOutputStream(uri) }
    }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    val loadedDetails = (details as? DetailsUi.Loaded)?.details
    val artifacts: List<BuildArtifactInfo> = loadedDetails?.artifacts ?: record.artifacts

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------------------------------------------------------------- status
        SectionCard(record.projectName) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BuildStatusPill(status = record.status)
                val seconds = record.durationSeconds ?: ((nowMillis - record.startedAtMillis) / 1000L).toInt()
                BodyText(Formatters.formatDuration(seconds))
            }
            if (!record.status.isTerminal && polling) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            record.detail?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (record.status == BuildStatus.SUCCESS) BodyText("GitHub Actions confirmed this build succeeded.")
            if (!record.status.isTerminal && polling) BodyText("Checking GitHub automatically every few seconds. It stops when the build finishes.")
        }

        if (record.pollingStopped && !record.status.isTerminal) {
            SectionCard("Not updating", tone = CardTone.Error) {
                BodyText(
                    "The app stopped checking GitHub, so the status shown is only the last one it saw " +
                        "(${record.status.name.lowercase()}). It is not a final result.",
                )
                if (needsReconnect) {
                    Button(onClick = onOpenGitHub) { Text("Reconnect GitHub") }
                } else {
                    Button(onClick = vm::refresh) { Text("Refresh now") }
                }
            }
        }

        // ---------------------------------------------------------------- progress
        SectionCard("Progress") {
            val order = listOf("Upload and start", "Queued on GitHub", "Building", "Finished")
            val rank = when (record.status) {
                BuildStatus.PREPARING -> 0
                BuildStatus.QUEUED -> 1
                BuildStatus.RUNNING -> 2
                else -> 3
            }
            order.forEachIndexed { index, label ->
                val mark = when {
                    index < rank -> "[done]"
                    index == rank && !record.status.isTerminal -> "[now] "
                    index == rank -> when (record.status) {
                        BuildStatus.SUCCESS -> "[done]"
                        BuildStatus.CANCELLED -> "[cancelled]"
                        else -> "[failed]"
                    }
                    else -> "[ ]   "
                }
                Text("$mark $label", fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            }
        }

        // ---------------------------------------------------------------- details
        SectionCard("Details") {
            BodyText("Build ID: ${record.id}")
            record.repoFullName?.let { BodyText("Repository: $it") }
            record.branch?.let { BodyText("Branch: $it") }
            record.projectDir?.let { BodyText("Folder: $it") }
            record.commitSha?.let { BodyText("Commit: ${it.take(7)}") }
            record.runId?.let { BodyText("Run id: $it") }
            BodyText("Started: ${Formatters.formatDateTime(record.startedAtMillis)}")
            BodyText("Finished: ${record.finishedAtMillis?.let { Formatters.formatDateTime(it) } ?: "not yet"}")
            val seconds = record.durationSeconds ?: ((nowMillis - record.startedAtMillis) / 1000L).toInt()
            BodyText("Duration: ${Formatters.formatDuration(seconds)}${if (record.status.isTerminal) "" else " (so far)"}")
        }

        // ---------------------------------------------------------------- steps
        if (record.runId != null) {
            StepsCard(details, loadedDetails, onRetryLoad = vm::loadDetails, onReconnect = onOpenGitHub, needsReconnect = needsReconnect)
        }

        // ---------------------------------------------------------------- logs and errors
        if (record.status.isTerminal && record.runId != null) {
            LogCard(
                record = record,
                logUi = log,
                fallbackExcerpt = record.errorExcerpt,
                onShowLog = vm::loadLog,
                onCopy = { text, what ->
                    clipboard.setText(AnnotatedString(text))
                    message = "$what copied"
                },
                onOpenRun = { record.runUrl?.let(uriHandler::openUri) },
            )
        } else record.errorExcerpt?.let {
            SectionCard("Error from the build log", tone = CardTone.Error) {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        // ---------------------------------------------------------------- artifacts and APKs
        if (record.status == BuildStatus.SUCCESS || artifacts.isNotEmpty()) {
            SectionCard("APK and artifacts", tone = if (artifacts.any { it.isApk && it.effectiveStatus(nowMillis) == ArtifactStatus.AVAILABLE }) CardTone.Success else CardTone.Normal) {
                loadedDetails?.artifactsNote?.let { BodyText(it) }
                if (artifacts.isEmpty()) {
                    BodyText(
                        when {
                            details is DetailsUi.Loading -> "Checking GitHub for artifacts..."
                            record.status == BuildStatus.SUCCESS -> "GitHub lists no artifact for this run, so there is no APK to download. Open the run on GitHub to check."
                            else -> "No artifacts."
                        },
                    )
                }
                artifacts.forEach { artifact ->
                    ArtifactRow(
                        artifact = artifact,
                        nowMillis = nowMillis,
                        state = downloads[DownloadKey(record.id, artifact.id)],
                        hasSavedCopy = saved.isNotEmpty(),
                        needsReconnect = needsReconnect,
                        onDownload = { vm.download(artifact)?.let { message = it } },
                        onCancel = { vm.cancelDownload(artifact) },
                        onDismiss = { vm.dismissDownload(artifact) },
                        onReconnect = onOpenGitHub,
                    )
                }
                if (saved.isNotEmpty()) {
                    Text("Saved on this phone", style = MaterialTheme.typography.titleSmall)
                    saved.forEach { apk ->
                        SavedApkRow(
                            apk = apk,
                            onShare = { ApkActions.share(context, apk)?.let { message = it } },
                            onSaveAs = {
                                pendingExport = apk
                                exportLauncher.launch(apk.fileName)
                            },
                            onDelete = { vm.deleteApk(apk) },
                        )
                    }
                    BodyText("To install, open the shared file from a file manager or save it to a folder, then tap it. Android may ask you to allow installs from that app.")
                }
            }
        }

        // ---------------------------------------------------------------- actions
        if (record.status.isTerminal) {
            val label = if (record.status == BuildStatus.SUCCESS) "Build again" else "Retry build"
            Button(
                onClick = vm::retryBuild,
                enabled = retry !is RetryUi.Starting && !needsReconnect,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (retry is RetryUi.Starting) "Starting..." else label) }
            BodyText("Retry uploads the project's current files again and starts a new build. This build stays in the history.")
            if (needsReconnect) OutlinedButton(onClick = onOpenGitHub, modifier = Modifier.fillMaxWidth()) { Text("Reconnect GitHub") }
        }
        record.runUrl?.let { url ->
            OutlinedButton(onClick = { uriHandler.openUri(url) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open run on GitHub (full logs)")
            }
        }
        OutlinedButton(onClick = vm::refresh, modifier = Modifier.fillMaxWidth()) {
            Text(if (record.status.isTerminal) "Refresh details" else "Check GitHub now")
        }
    }
}

@Composable
private fun StepsCard(
    ui: DetailsUi,
    details: BuildDetails?,
    onRetryLoad: () -> Unit,
    onReconnect: () -> Unit,
    needsReconnect: Boolean,
) {
    SectionCard("Steps", tone = if (details?.failedSteps?.isNotEmpty() == true) CardTone.Error else CardTone.Normal) {
        when {
            details != null -> {
                details.jobsNote?.let { BodyText(it) }
                if (details.jobs.isEmpty() && details.jobsNote == null) BodyText("GitHub has not listed any steps yet.")
                details.jobs.forEach { job ->
                    Text(job.name, style = MaterialTheme.typography.titleSmall)
                    job.steps.forEach { step ->
                        val mark = when (step.conclusion) {
                            "success" -> "[ok]    "
                            "failure" -> "[FAILED]"
                            "skipped" -> "[skip]  "
                            "cancelled" -> "[cancel]"
                            null -> "[...]   "
                            else -> "[${step.conclusion.take(6)}]"
                        }
                        Text("$mark ${step.name}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    }
                }
                if (details.failedSteps.isNotEmpty()) {
                    Text("Failed at: " + details.failedSteps.joinToString { "\"${it.stepName}\"" }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            ui is DetailsUi.Loading -> BodyText("Loading steps from GitHub...")
            ui is DetailsUi.Error -> {
                BodyText(ui.message)
                if (needsReconnect) Button(onClick = onReconnect) { Text("Reconnect GitHub") }
                else if (ui.retryable) OutlinedButton(onClick = onRetryLoad) { Text("Try again") }
            }
            else -> BodyText("Steps appear here once GitHub has started the run.")
        }
    }
}

@Composable
private fun LogCard(
    record: BuildRecord,
    logUi: LogUi,
    fallbackExcerpt: String?,
    onShowLog: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenRun: () -> Unit,
) {
    val failed = record.status == BuildStatus.FAILED || record.status == BuildStatus.CANCELLED
    SectionCard(if (failed) "Errors and log" else "Build log", tone = if (record.status == BuildStatus.FAILED) CardTone.Error else CardTone.Normal) {
        when (logUi) {
            LogUi.Hidden -> {
                if (failed && fallbackExcerpt != null) {
                    Text(fallbackExcerpt, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    OutlinedButton(onClick = { onCopy(fallbackExcerpt, "Error") }) { Text("Copy error") }
                }
                OutlinedButton(onClick = onShowLog) { Text("Show build log") }
            }
            LogUi.Loading -> {
                BodyText("Loading the log from GitHub...")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is LogUi.Shown -> {
                val view = logUi.view
                if (view.errors.isNotEmpty()) {
                    Text("What went wrong", style = MaterialTheme.typography.titleSmall)
                    view.errors.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    OutlinedButton(onClick = { onCopy(view.errors.joinToString("\n"), "Errors") }) { Text("Copy errors") }
                } else if (view.text != null && failed) {
                    BodyText("No compiler errors were recognised in the log. The end of the log is shown below.")
                }
                if (view.text != null) {
                    Text("End of the log", style = MaterialTheme.typography.titleSmall)
                    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Text(view.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                    OutlinedButton(onClick = { onCopy(view.text, "Log") }) { Text("Copy log") }
                    BodyText("Secrets and tokens are removed from this text. Only the last part of the log is shown.")
                }
                view.note?.let { BodyText(it) }
                if (view.text == null) {
                    fallbackExcerpt?.let {
                        Text("Saved when the build finished:", style = MaterialTheme.typography.titleSmall)
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onShowLog) { Text("Try again") }
                        OutlinedButton(onClick = onOpenRun) { Text("Open run on GitHub") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(
    artifact: BuildArtifactInfo,
    nowMillis: Long,
    state: DownloadState?,
    hasSavedCopy: Boolean,
    needsReconnect: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onReconnect: () -> Unit,
) {
    val status = artifact.effectiveStatus(nowMillis)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(artifact.name, style = MaterialTheme.typography.titleSmall)
        val expiry = artifact.expiresAtMillis?.let { " - expires ${Formatters.timeUntil(nowMillis, it)} (${Formatters.formatDate(it)})" }.orEmpty()
        BodyText(
            when (status) {
                ArtifactStatus.AVAILABLE -> "${Formatters.formatBytes(artifact.sizeBytes)}$expiry"
                ArtifactStatus.EXPIRED -> "${Formatters.formatBytes(artifact.sizeBytes)} - expired on GitHub, it can no longer be downloaded"
                ArtifactStatus.DELETED -> "Deleted on GitHub, it can no longer be downloaded"
            },
        )
        if (!artifact.isApk) {
            BodyText("Build log archive (not an APK). Read the log above or open the run on GitHub.")
            return@Column
        }
        when (state) {
            is DownloadState.Active -> {
                val p = state.progress
                val fraction = p.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                BodyText(progressText(p))
                OutlinedButton(onClick = onCancel) { Text("Cancel download") }
            }
            is DownloadState.Failed -> {
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.kind == ArtifactDownloadException.Kind.TOKEN_REJECTED || state.kind == ArtifactDownloadException.Kind.NOT_CONNECTED ->
                            Button(onClick = onReconnect) { Text("Reconnect GitHub") }
                        state.retryable -> Button(onClick = onDownload) { Text("Try again") }
                    }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
            DownloadState.Cancelled -> {
                BodyText("Download cancelled. Nothing was saved.")
                if (status == ArtifactStatus.AVAILABLE) Button(onClick = onDownload) { Text("Download again") }
            }
            is DownloadState.Finished, null -> {
                if (state is DownloadState.Finished) BodyText("Downloaded and verified.")
                if (status == ArtifactStatus.AVAILABLE) {
                    Button(onClick = onDownload, enabled = !needsReconnect) {
                        Text(if (hasSavedCopy || state is DownloadState.Finished) "Download again" else "Download APK")
                    }
                    if (needsReconnect) BodyText("Reconnect GitHub to download.")
                }
            }
        }
    }
}

private fun progressText(p: DownloadProgress): String = when (p.phase) {
    DownloadProgress.Phase.CONNECTING -> "Connecting to GitHub..."
    DownloadProgress.Phase.VERIFYING -> "Checking the file..."
    DownloadProgress.Phase.SAVING -> "Saving the APK..."
    DownloadProgress.Phase.DOWNLOADING -> {
        val total = p.totalBytes
        if (total != null && total > 0L) "${Formatters.formatBytes(p.bytesDone)} of ${Formatters.formatBytes(total)}"
        else "${Formatters.formatBytes(p.bytesDone)} downloaded"
    }
}

@Composable
private fun SavedApkRow(apk: StoredApk, onShare: () -> Unit, onSaveAs: () -> Unit, onDelete: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${apk.fileName} (${Formatters.formatBytes(apk.sizeBytes)})", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onShare) { Text("Share") }
            OutlinedButton(onClick = onSaveAs) { Text("Save to...") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}
