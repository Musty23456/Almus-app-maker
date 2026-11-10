package com.almus.appcreatorpro.ui.agent

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.agent.AgentLogEntry
import com.almus.appcreatorpro.agent.AgentLogLevel
import com.almus.appcreatorpro.agent.AgentPhase
import com.almus.appcreatorpro.agent.AgentRunState
import com.almus.appcreatorpro.agent.AgentRunner
import com.almus.appcreatorpro.agent.AgentTask
import com.almus.appcreatorpro.agent.ReadinessStatus
import com.almus.appcreatorpro.agent.TaskStatus
import com.almus.appcreatorpro.ai.ChangeKind
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.ai.ChangeFileRow
import com.almus.appcreatorpro.ui.components.ConfirmDialog

/** The agent workspace: describe -> approve the plan -> watch tasks, logs and streamed output -> preview file changes -> apply or roll back. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentWorkspaceScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenCloudBuild: () -> Unit,
) {
    val vm: AgentViewModel = viewModel(key = "agent-$projectId", factory = AppViewModelProvider.agentFactory(projectId))
    val runner by vm.runner.collectAsStateWithLifecycle()
    val availability by vm.availability.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI agent") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val r = runner
        if (r == null) {
            Column(Modifier.padding(padding).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val s by r.state.collectAsStateWithLifecycle()
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("Agent", "Logs (${s.logs.size})", "Files${s.changeSet?.let { " (${it.changes.size})" }.orEmpty()}", "Report").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> AgentTab(r, s, availability, onOpenSettings, onOpenDebug, vm::refreshAvailability, vm::rollback) { tab = 2 }
                1 -> LogsTab(s.logs)
                2 -> FilesTab(s, vm::apply)
                else -> ReportTab(s, onOpenDebug, onOpenCloudBuild)
            }
        }
    }
}

private fun phaseText(s: AgentRunState): String = when (s.phase) {
    AgentPhase.IDLE -> "Ready"
    AgentPhase.PLANNING -> "Planning"
    AgentPhase.AWAITING_APPROVAL -> "Waiting for your approval of the plan"
    AgentPhase.RUNNING -> "Working"
    AgentPhase.AWAITING_REVIEW -> "Done - review the files before anything is applied"
    AgentPhase.APPLIED -> "Applied to your project"
    AgentPhase.FAILED -> "Stopped: something went wrong"
    AgentPhase.CANCELLED -> "Cancelled"
}

@Composable
private fun AgentTab(
    r: AgentRunner,
    s: AgentRunState,
    availability: AiAvailability?,
    onOpenSettings: () -> Unit,
    onOpenDebug: () -> Unit,
    onRefresh: () -> Unit,
    onRollback: (Boolean) -> Unit,
    onShowFiles: () -> Unit,
) {
    var goal by rememberSaveable { mutableStateOf("") }
    var feedback by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmRollback by remember { mutableStateOf(false) }
    val notReady = availability as? AiAvailability.NotReady

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        notReady?.let {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(it.reason)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenSettings) { Text("Set up AI") }
                        TextButton(onClick = onRefresh) { Text("Check again") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(phaseText(s), style = MaterialTheme.typography.titleMedium)
                s.stage?.let { Text("Step ${it.step} of 10: ${it.label}", style = MaterialTheme.typography.bodySmall) }
                if (s.tasks.isNotEmpty()) {
                    LinearProgressIndicator(progress = { s.taskProgress }, modifier = Modifier.fillMaxWidth())
                    Text("${s.tasks.count { it.status == TaskStatus.DONE }} of ${s.tasks.size} tasks done - ${s.modelCalls} AI request(s)", style = MaterialTheme.typography.bodySmall)
                } else if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        if (s.busy && s.liveTail.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Live output (${s.streamedChars} characters)", style = MaterialTheme.typography.labelMedium)
                    Text(s.liveTail, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.horizontalScroll(rememberScrollState()))
                }
            }
        }

        when (s.phase) {
            AgentPhase.IDLE, AgentPhase.APPLIED -> {
                if (s.phase == AgentPhase.APPLIED) {
                    Text("The agent's files are in your project. You can roll the whole run back, or start a new run.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { confirmRollback = true }) { Text("Roll back this run") }
                }
                Text("Describe the change or the app in plain language. The agent writes a plan for you to approve, works through it, checks its own files, and shows you every change before anything touches your project.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(goal, { goal = it }, label = { Text("What should the agent do?") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = s.options.autoCorrect, onCheckedChange = { r.setAutoCorrect(it) })
                    Text("Let the agent fix problems it finds (in its own staging area only)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                Button(onClick = { if (r.start(goal, s.options)) goal = "" }, enabled = notReady == null && goal.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Plan with the agent") }
            }

            AgentPhase.PLANNING -> {
                OutlinedButton(onClick = { r.cancel() }) { Text("Cancel") }
            }

            AgentPhase.AWAITING_APPROVAL -> {
                s.plan?.let { p ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Understood requirements", style = MaterialTheme.typography.titleSmall)
                            Text(p.understanding.ifBlank { p.summary })
                            if (p.assumptions.isNotEmpty()) { Text("Assumptions", style = MaterialTheme.typography.titleSmall); p.assumptions.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) } }
                            if (p.architecture.isNotBlank()) { Text("Architecture", style = MaterialTheme.typography.titleSmall); Text(p.architecture, style = MaterialTheme.typography.bodySmall) }
                            if (p.dependencies.isNotEmpty()) { Text("Recommended dependencies", style = MaterialTheme.typography.titleSmall); p.dependencies.forEach { Text("- ${it.coordinate} ${it.reason}", style = MaterialTheme.typography.bodySmall) } }
                            if (p.risks.isNotEmpty()) { Text("Risks", style = MaterialTheme.typography.titleSmall); p.risks.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) } }
                            p.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text("Tasks (remove any you do not want)", style = MaterialTheme.typography.titleSmall)
                s.tasks.forEach { t -> TaskRow(t, false) { TextButton(onClick = { r.removeTask(t.id) }) { Text("Remove") } } }
                Button(onClick = { r.approvePlan() }, enabled = s.tasks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Approve plan and start") }
                OutlinedTextField(feedback, { feedback = it }, label = { Text("Ask for a different plan") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { if (r.revisePlan(feedback)) feedback = "" }, enabled = feedback.isNotBlank()) { Text("Revise plan") }
                TextButton(onClick = { confirmDiscard = true }) { Text("Discard") }
            }

            AgentPhase.RUNNING -> {
                s.tasks.forEach { TaskRow(it, it.id == s.currentTaskId) {} }
                OutlinedButton(onClick = { r.cancel() }) { Text("Cancel") }
            }

            AgentPhase.FAILED, AgentPhase.CANCELLED -> {
                s.tasks.forEach { TaskRow(it, false) {} }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { r.retry() }) { Text(if (s.phase == AgentPhase.FAILED) "Try again" else "Resume") }
                    if (s.tasks.any { it.status == TaskStatus.FAILED }) OutlinedButton(onClick = { r.skipFailedTasks() }) { Text("Skip failed task") }
                }
                TextButton(onClick = { confirmDiscard = true }) { Text("Discard this run") }
            }

            AgentPhase.AWAITING_REVIEW -> {
                s.tasks.forEach { TaskRow(it, false) {} }
                s.report?.let { Text("${it.filesCreated} new, ${it.filesChanged} changed file(s) - ${it.errors} error(s), ${it.warnings} warning(s)", style = MaterialTheme.typography.titleSmall) }
                Button(onClick = onShowFiles, modifier = Modifier.fillMaxWidth()) { Text("Review the file changes") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.validation?.errors?.isNotEmpty() == true) OutlinedButton(onClick = { r.fixProblems() }) { Text("Fix problems") }
                    OutlinedButton(onClick = onOpenDebug) { Text("Open build debugger") }
                }
                TextButton(onClick = { confirmDiscard = true }) { Text("Discard this run") }
            }
        }

        if (s.runId != null && s.phase != AgentPhase.AWAITING_APPROVAL && s.phase != AgentPhase.IDLE) {
            OutlinedTextField(note, { note = it }, label = { Text("Add a note for the agent (used by the next tasks)") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { if (r.sendMessage(note)) note = "" }, enabled = note.isNotBlank()) { Text("Send note") }
        }
        if (s.runId != null && s.messages.isNotEmpty()) {
            Text("Conversation", style = MaterialTheme.typography.titleSmall)
            s.messages.takeLast(6).forEach { Text("- ${it.text.take(200)}", style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard this run?",
            message = if (s.hasUnappliedWork) "The agent's staged files will be lost. Your project was not changed." else "The plan and log of this run will be removed.",
            confirmLabel = "Discard",
            onConfirm = { confirmDiscard = false; r.discardRun() },
            onDismiss = { confirmDiscard = false },
            destructive = true,
        )
    }
    if (confirmRollback) {
        ConfirmDialog(
            title = "Roll back the agent's files?",
            message = "The files the agent applied are put back as they were. If you edited any of them since, nothing is rolled back.",
            confirmLabel = "Roll back",
            onConfirm = { confirmRollback = false; onRollback(false) },
            onDismiss = { confirmRollback = false },
            destructive = true,
        )
    }
}

@Composable
private fun TaskRow(t: AgentTask, current: Boolean, trailing: @Composable () -> Unit) {
    val mark = when (t.status) {
        TaskStatus.PENDING -> "[ ]"
        TaskStatus.RUNNING -> "[...]"
        TaskStatus.DONE -> "[x]"
        TaskStatus.FAILED -> "[!]"
        TaskStatus.SKIPPED -> "[-]"
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("$mark ${t.id} - ${t.title}", style = MaterialTheme.typography.bodyMedium)
                Text(t.kind.label + if (t.detail.isNotBlank()) ": ${t.detail.take(160)}" else "", style = MaterialTheme.typography.bodySmall)
                if (t.note.isNotBlank()) Text(t.note.take(200), style = MaterialTheme.typography.bodySmall, color = if (t.status == TaskStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (t.touched.isNotEmpty()) Text(t.touched.joinToString(), style = MaterialTheme.typography.labelSmall)
            }
            trailing()
        }
    }
}

@Composable
private fun LogsTab(logs: List<AgentLogEntry>) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { clipboard.setText(AnnotatedString(logs.joinToString("\n") { "[${it.level.name}] ${it.text}" })) }, enabled = logs.isNotEmpty()) { Text("Copy log") }
        }
        if (logs.isEmpty()) Text("Nothing logged yet.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.fillMaxSize()) {
            items(logs.asReversed()) { l ->
                val color = when (l.level) {
                    AgentLogLevel.ERROR -> MaterialTheme.colorScheme.error
                    AgentLogLevel.WARN -> Color(0xFFB26A00)
                    AgentLogLevel.STEP -> MaterialTheme.colorScheme.primary
                    AgentLogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                }
                Text(l.text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun FilesTab(s: AgentRunState, onApply: (Set<String>) -> Unit) {
    val set = s.changeSet
    if (set == null) {
        Column(Modifier.padding(16.dp)) { Text(if (s.busy) "File changes appear here when the agent is done." else "The agent has no file changes to show.", style = MaterialTheme.typography.bodyMedium) }
        return
    }
    val selected = remember(set.id) { mutableStateListOf<String>().apply { addAll(set.paths) } }
    var confirm by remember { mutableStateOf(false) }
    val canApply = s.phase == AgentPhase.AWAITING_REVIEW
    val overwrites = set.changes.filter { it.path in selected && it.kind == ChangeKind.MODIFY }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Proposed changes - nothing is in your project yet", style = MaterialTheme.typography.titleSmall)
        Text("Tap a file for the before/after diff.", style = MaterialTheme.typography.bodySmall)
        set.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        set.changes.forEach { c ->
            ChangeFileRow(c, checked = c.path in selected, enabled = canApply, onToggle = { on -> if (on) { if (c.path !in selected) selected.add(c.path) } else selected.remove(c.path) })
        }
        Button(
            onClick = { if (overwrites.isNotEmpty()) confirm = true else onApply(selected.toSet()) },
            enabled = canApply && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (s.phase == AgentPhase.APPLIED) "Already applied" else "Apply ${selected.size} file(s) to the project") }
    }
    if (confirm) {
        ConfirmDialog(
            title = "Overwrite ${overwrites.size} existing file(s)?",
            message = "These files already exist and will be replaced: ${overwrites.take(6).joinToString { it.path }}${if (overwrites.size > 6) ", ..." else ""}. You can roll this back afterwards.",
            confirmLabel = "Apply",
            onConfirm = { confirm = false; onApply(selected.toSet()) },
            onDismiss = { confirm = false },
            destructive = true,
        )
    }
}

@Composable
private fun ReportTab(s: AgentRunState, onOpenDebug: () -> Unit, onOpenCloudBuild: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val report = s.report
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (report == null) {
            Text("The completion report appears here when the agent has finished.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        s.readiness?.let { r ->
            Text(if (r.ready) "Ready for a cloud build" else "Not ready for a cloud build yet", style = MaterialTheme.typography.titleSmall)
            r.items.filter { it.status != ReadinessStatus.OK }.forEach { Text("${it.status.name}: ${it.label} ${it.detail}", style = MaterialTheme.typography.bodySmall) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(report.markdown)) }) { Text("Copy report") }
            OutlinedButton(onClick = onOpenCloudBuild) { Text("Cloud build") }
            OutlinedButton(onClick = onOpenDebug) { Text("Debugger") }
        }
        Text(report.markdown, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
    }
}
