package com.almus.appcreatorpro.ui.debug

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.debug.AiOutcome
import com.almus.appcreatorpro.debug.AiTask
import com.almus.appcreatorpro.debug.BuildIssue
import com.almus.appcreatorpro.debug.DebugSession
import com.almus.appcreatorpro.debug.DiffKind
import com.almus.appcreatorpro.debug.FixEntry
import com.almus.appcreatorpro.debug.FixStatus
import com.almus.appcreatorpro.debug.IssueFilter
import com.almus.appcreatorpro.debug.IssueSource
import com.almus.appcreatorpro.debug.LogExportFormat
import com.almus.appcreatorpro.debug.LogLevel
import com.almus.appcreatorpro.debug.LogcatAvailability
import com.almus.appcreatorpro.debug.Severity
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.ui.theme.SuccessGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("Errors", "Log", "Fixes")

/**
 * Phase 8 debugging screen: error dashboard (build log + static code checks), log viewer, and fix management.
 * [onOpenFile] opens the editor at a file/line/column.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    projectId: String,
    buildId: String?,
    onBack: () -> Unit,
    onOpenFile: (path: String, line: Int?, column: Int?) -> Unit,
) {
    val vm: DebugViewModel = viewModel(key = "debug-$projectId-${buildId.orEmpty()}", factory = AppViewModelProvider.debugFactory(projectId, buildId))
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()

    LaunchedEffect(exportMessage) {
        exportMessage?.let { snackbar.showSnackbar(it); vm.consumeExportMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Errors & debugging") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = vm::refresh) { Text("Re-check") } },
            )
        },
    ) { padding ->
        val s = session
        if (s == null) {
            LoadingView(modifier = Modifier.padding(padding), message = "Preparing...")
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TABS.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            DebugBody(vm, s, tab, snackbar, onOpenFile)
        }
    }
}

@Composable
private fun DebugBody(vm: DebugViewModel, s: DebugSession, tab: Int, snackbar: SnackbarHostState, onOpenFile: (String, Int?, Int?) -> Unit) {
    val state by s.state.collectAsStateWithLifecycle()
    val entries by s.fixes.entries.collectAsStateWithLifecycle()
    val undoDepth by s.fixes.undoDepth.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }

    preview?.let { p -> PreviewDialog(p, onApply = { vm.apply(p.entry.proposal.id) }, onReject = { vm.reject(p.entry.proposal.id) }, onClose = vm::closePreview) }

    when (tab) {
        0 -> ErrorsTab(vm, s, state, entries, onOpenFile)
        1 -> LogTab(vm, s, state, snackbar)
        else -> FixesTab(vm, entries, undoDepth)
    }
}

// ------------------------------------------------------------------------------------------ errors

@Composable
private fun ErrorsTab(
    vm: DebugViewModel,
    s: DebugSession,
    state: com.almus.appcreatorpro.debug.DebugState,
    entries: List<FixEntry>,
    onOpenFile: (String, Int?, Int?) -> Unit,
) {
    val issues = state.issues
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("What this shows") {
                BodyText("Build errors come from the real GitHub build log. Code checks are rule-based heuristics that read your files without compiling them: they can miss problems and can be wrong, so treat them as hints. This is not a runtime debugger.")
                state.buildFailed?.let { BodyText(if (it) "The build log says the build failed." else "The build log says the build succeeded.") }
                state.logNote?.let { BodyText(it) }
                if (state.loadingLog || state.runningChecks) BodyText(if (state.loadingLog) "Reading the build log..." else "Checking project files...")
                if (state.checksRan) BodyText("Code checks looked at ${state.filesChecked} file(s).")
                if (state.omittedWarnings > 0) BodyText("${state.omittedWarnings} more warning(s) were left out.")
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IssueFilter.values().forEach { f ->
                    FilterChip(selected = state.issueFilter == f, onClick = { vm.setIssueFilter(f) }, label = { Text(f.label) })
                }
            }
        }
        if (issues.isEmpty() && !state.loadingLog && !state.runningChecks) {
            item {
                SectionCard("No problems found", tone = CardTone.Success) {
                    BodyText(if (state.checksRan || state.logLoaded) "Nothing was reported by the selected sources. That does not prove the app works." else "Nothing has been analyzed yet.")
                    OutlinedButton(onClick = vm::runChecks) { Text("Run code checks") }
                }
            }
        }
        items(issues, key = { it.id + it.source.name }) { issue -> IssueCard(vm, s, issue, entries, onOpenFile) }
    }
}

@Composable
private fun IssueCard(vm: DebugViewModel, s: DebugSession, issue: BuildIssue, entries: List<FixEntry>, onOpenFile: (String, Int?, Int?) -> Unit) {
    val fixes = entries.filter { it.proposal.issueId == issue.id && it.status == FixStatus.PENDING }
    val tone = if (issue.severity == Severity.ERROR) CardTone.Error else CardTone.Normal
    SectionCard("${issue.severity.label} - ${issue.category.label}", tone = tone, badge = if (issue.source == IssueSource.STATIC_ANALYSIS) "Check" else null) {
        SelectionContainer { Text(issue.message, style = MaterialTheme.typography.bodyLarge) }
        issue.locationLabel?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        issue.suggestion?.let { Text("Suggestion: $it", style = MaterialTheme.typography.bodyMedium) }
        if (issue.source == IssueSource.STATIC_ANALYSIS) BodyText("Confidence: ${issue.confidence.label} (heuristic)")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val target = s.targetFor(issue)
            if (target != null) Button(onClick = { onOpenFile(target.path, target.line, target.column) }) { Text("Open in editor") }
            fixes.firstOrNull()?.let { f -> OutlinedButton(onClick = { vm.showPreview(f.proposal.id) }) { Text("Preview fix") } }
        }
    }
}

// ------------------------------------------------------------------------------------------ log

@Composable
private fun LogTab(vm: DebugViewModel, s: DebugSession, state: com.almus.appcreatorpro.debug.DebugState, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveText = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch { vm.postExportMessage(writeTo(context, uri, vm.exportText(LogExportFormat.TEXT))) }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { vm.postExportMessage(writeTo(context, uri, vm.exportText(LogExportFormat.JSON))) }
    }
    val f = state.logFilter
    val visible = state.visibleLog
    val stats = state.logStats

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            OutlinedTextField(
                value = f.query, onValueChange = vm::setQuery, label = { Text("Search the log") }, singleLine = true,
                isError = f.queryError != null, supportingText = { f.queryError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogLevel.values().reversed().forEach { l ->
                    val n = when (l) { LogLevel.ERROR -> stats.errors; LogLevel.WARNING -> stats.warnings; LogLevel.INFO -> stats.info; LogLevel.DEBUG -> stats.debug }
                    FilterChip(selected = l in f.levels, onClick = { vm.toggleLevel(l) }, label = { Text("${l.label} $n") })
                }
                FilterChip(selected = f.useRegex, onClick = { vm.setRegex(!f.useRegex) }, label = { Text("Regex") })
                FilterChip(selected = f.caseSensitive, onClick = { vm.setCaseSensitive(!f.caseSensitive) }, label = { Text("Aa") })
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(vm.copyText()))
                    vm.postExportMessage("Copied ${visible.size} line(s)")
                }, enabled = visible.isNotEmpty()) { Text("Copy") }
                OutlinedButton(onClick = { saveText.launch(vm.exportFileName(LogExportFormat.TEXT)) }, enabled = visible.isNotEmpty()) { Text("Export .txt") }
                OutlinedButton(onClick = { saveJson.launch(vm.exportFileName(LogExportFormat.JSON)) }, enabled = visible.isNotEmpty()) { Text("Export .json") }
            }
        }
        item {
            val lc = s.logcat.availability
            if (lc is LogcatAvailability.Unavailable) BodyText("Live Logcat: ${lc.reason}")
            BodyText("${visible.size} of ${stats.total} line(s) shown. Secrets and tokens are removed from the log before it is shown, copied or exported.")
        }
        if (state.entries.isEmpty()) item { BodyText(state.logNote ?: "There is no build log to show.") }
        items(visible, key = { it.index }) { e ->
            val color = when (e.level) {
                LogLevel.ERROR -> MaterialTheme.colorScheme.error
                LogLevel.WARNING -> Color(0xFFB26A00)
                LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
            }
            SelectionContainer {
                Text(e.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = if (e.level == LogLevel.ERROR) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

private suspend fun writeTo(context: Context, uri: android.net.Uri, text: String): String = withContext(Dispatchers.IO) {
    try {
        val out = context.contentResolver.openOutputStream(uri) ?: return@withContext "Could not open the file to save."
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        "Saved"
    } catch (e: Exception) {
        "Could not save: ${e.message.orEmpty()}"
    }
}

// ------------------------------------------------------------------------------------------ fixes

@Composable
private fun FixesTab(vm: DebugViewModel, entries: List<FixEntry>, undoDepth: Int) {
    val ai by vm.ai.collectAsStateWithLifecycle()
    val aiNote by vm.aiNote.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Suggested fixes") {
                BodyText("Fixes are suggestions. You see the before and after, and nothing changes until you press Apply. Apply is refused if the file changed after the suggestion was made. Undo works for this session.")
                OutlinedButton(onClick = vm::undo, enabled = undoDepth > 0) { Text(if (undoDepth > 0) "Undo last fix ($undoDepth)" else "Nothing to undo") }
            }
        }
        if (entries.isEmpty()) item { BodyText("No fixes are available. Run the code checks from the Errors tab.") }
        items(entries, key = { it.proposal.id }) { e ->
            SectionCard(e.proposal.title, badge = e.proposal.origin.label, tone = if (e.status == FixStatus.APPLIED) CardTone.Success else CardTone.Normal) {
                BodyText(e.proposal.explanation)
                Text(e.proposal.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                BodyText("Status: " + when (e.status) { FixStatus.PENDING -> "waiting for you"; FixStatus.APPLIED -> "applied"; FixStatus.REJECTED -> "rejected" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (e.status) {
                        FixStatus.PENDING -> {
                            Button(onClick = { vm.showPreview(e.proposal.id) }) { Text("Preview") }
                            TextButton(onClick = { vm.reject(e.proposal.id) }) { Text("Reject") }
                        }
                        FixStatus.REJECTED -> TextButton(onClick = { vm.restore(e.proposal.id) }) { Text("Bring back") }
                        FixStatus.APPLIED -> Unit
                    }
                }
            }
        }
        item { AiCard(vm, ai, aiNote) }
    }
}

@Composable
private fun AiCard(vm: DebugViewModel, ai: AiUi, note: String?) {
    SectionCard("AI debugging") {
        BodyText(note ?: "An AI provider is connected. Nothing is sent until you confirm.")
        if (note == null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiTask.values().forEach { t -> OutlinedButton(onClick = { vm.prepareAi(t, includeCode = t != AiTask.EXPLAIN) }) { Text(t.label) } }
            }
        }
        when (ai) {
            AiUi.Idle -> Unit
            AiUi.Working -> BodyText("Waiting for the answer...")
            is AiUi.Confirm -> AlertDialog(
                onDismissRequest = vm::cancelAi,
                title = { Text("Send to the AI?") },
                text = {
                    Text(
                        "This sends ${ai.request.issues.size} error(s), a redacted log excerpt" +
                            (if (ai.request.containsSourceCode) " and code from ${ai.request.files.size} file(s): " + ai.request.files.joinToString { it.path } else "") + " to the AI provider.",
                    )
                },
                confirmButton = { TextButton(onClick = { vm.sendAi(ai.request) }) { Text("Send") } },
                dismissButton = { TextButton(onClick = vm::cancelAi) { Text("Cancel") } },
            )
            is AiUi.Done -> when (val o = ai.outcome) {
                is AiOutcome.Answer -> {
                    Text(o.response.summary, fontWeight = FontWeight.Bold)
                    o.response.rootCause?.let { BodyText("Cause: $it") }
                    BodyText(o.response.explanation)
                    if (o.rejectedPatches.isNotEmpty()) BodyText("${o.rejectedPatches.size} suggested change(s) did not match your files and were not offered.")
                }
                is AiOutcome.Unavailable -> BodyText(o.reason)
                is AiOutcome.Failed -> BodyText(o.message)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------ preview

@Composable
private fun PreviewDialog(p: FixPreviewUi, onApply: () -> Unit, onReject: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(p.entry.proposal.title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(p.entry.proposal.path, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (p.preview == null) {
                    Text(p.unavailableReason.orEmpty(), color = MaterialTheme.colorScheme.error)
                } else {
                    Text("Before / after (- removed, + added):", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        p.preview.diff.forEach { d ->
                            val (prefix, color) = when (d.kind) {
                                DiffKind.REMOVED -> "- " to MaterialTheme.colorScheme.error
                                DiffKind.ADDED -> "+ " to SuccessGreen
                                DiffKind.CONTEXT -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            val n = (d.oldLine ?: d.newLine)?.toString().orEmpty().padStart(4)
                            Text("$n $prefix${d.text}", color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onApply, enabled = p.preview != null) { Text("Apply") } },
        dismissButton = {
            Row {
                TextButton(onClick = onReject) { Text("Reject") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}
