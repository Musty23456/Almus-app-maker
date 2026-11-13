package com.almus.appcreatorpro.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.ChatMarkdown
import com.almus.appcreatorpro.ai.ChatTurn
import com.almus.appcreatorpro.ai.TurnState
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.ai.CodeBlockView
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.workspace.RepoWorkspace
import com.almus.appcreatorpro.workspace.WorkspacePlans
import com.almus.appcreatorpro.workspace.WorkspaceStage

/** The AI conversation about ONE imported repository, with project switching, sync and the plan approval step. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoWorkspaceScreen(
    workspaceId: String,
    onBack: () -> Unit,
    onSwitchWorkspace: (String) -> Unit,
    onOpenProjects: () -> Unit,
    onOpenEditor: (projectId: String) -> Unit,
    onOpenAgent: (projectId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm: RepoWorkspaceViewModel = viewModel(key = "ws-$workspaceId", factory = AppViewModelProvider.workspaceFactory(workspaceId))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var switchMenu by remember { mutableStateOf(false) }
    var confirmSync by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    val workspace = state.workspace
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workspace?.fullName ?: "Workspace", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    Box {
                        TextButton(onClick = { switchMenu = true }) { Text("Switch") }
                        DropdownMenu(expanded = switchMenu, onDismissRequest = { switchMenu = false }) {
                            if (state.others.isEmpty()) DropdownMenuItem(text = { Text("No other workspaces") }, onClick = { switchMenu = false })
                            state.others.forEach { w ->
                                DropdownMenuItem(
                                    text = { Text("${w.fullName} (${w.branch})") },
                                    onClick = { switchMenu = false; onSwitchWorkspace(w.id) },
                                )
                            }
                            DropdownMenuItem(text = { Text("All GitHub projects...") }, onClick = { switchMenu = false; onOpenProjects() })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> LoadingView(message = "Opening workspace...")
                state.problem != null -> EmptyView(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    title = "Workspace not available",
                    message = state.problem.orEmpty(),
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "Open GitHub projects",
                    onAction = onOpenProjects,
                )
                workspace != null -> WorkspaceBody(
                    state = state,
                    workspace = workspace,
                    vm = vm,
                    onAskSync = { confirmSync = true },
                    onAskClear = { confirmClear = true },
                    onOpenEditor = { onOpenEditor(workspace.projectId) },
                    onOpenAgent = { onOpenAgent(workspace.projectId) },
                    onOpenSettings = onOpenSettings,
                    onStartAgent = { vm.startAgent(onOpenAgent) },
                )
            }
        }
    }

    if (confirmSync) {
        ConfirmDialog(
            title = "Update local copy?",
            message = "This replaces the project's files with the newest commit from GitHub. Changes you or the AI made on this phone that are not on GitHub will be replaced. A safety backup is saved first (see Backups).",
            confirmLabel = "Update",
            destructive = true,
            onConfirm = { confirmSync = false; vm.syncNow() },
            onDismiss = { confirmSync = false },
        )
    }
    if (confirmClear) {
        ConfirmDialog(
            title = "Clear this conversation?",
            message = "The messages about this repository are deleted. The project files are not touched.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = { confirmClear = false; vm.clearChat() },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun WorkspaceBody(
    state: WorkspaceUi,
    workspace: RepoWorkspace,
    vm: RepoWorkspaceViewModel,
    onAskSync: () -> Unit,
    onAskClear: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenAgent: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartAgent: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var input by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.turns.size, state.turns.lastOrNull()?.text?.length) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.size)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { HeaderCard(workspace, state.sync, vm, onAskSync, onOpenEditor, onOpenAgent, onAskClear, onOpenSettings) }
            if (state.turns.isEmpty()) {
                item {
                    SectionCard("Talk about this project") {
                        BodyText("The AI sees this repository's structure and key files. Say what you want, for example \"I want to improve the UI\". It will ask questions and write a plan first. It cannot change files until you approve the plan.")
                        listOf("I want to improve the UI", "Explain how this app is built", "Find problems in this code").forEach { suggestion ->
                            OutlinedButton(onClick = { vm.send(suggestion) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(suggestion) }
                        }
                    }
                }
            }
            val lastIndex = state.turns.lastIndex
            items(count = state.turns.size) { i ->
                val turn = state.turns[i]
                TurnBubble(turn, isLast = i == lastIndex, busy = state.busy, onCopy = { clipboard.setText(AnnotatedString(it)) }, onRetry = vm::retry)
            }
            state.notice?.let { notice ->
                item { Text(notice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }

        StageBar(state, onApprove = vm::approvePlan, onStartAgent = onStartAgent)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask about ${workspace.repoName}...") },
                maxLines = 4,
            )
            if (state.busy) {
                OutlinedButton(onClick = vm::cancel) { Text("Stop") }
            } else {
                IconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank(),
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}

@Composable
private fun HeaderCard(
    workspace: RepoWorkspace,
    sync: SyncUi,
    vm: RepoWorkspaceViewModel,
    onAskSync: () -> Unit,
    onOpenEditor: () -> Unit,
    onOpenAgent: () -> Unit,
    onAskClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    SectionCard("Project") {
        BodyText(
            "${if (workspace.isPrivate) "Private" else "Public"}  |  branch ${workspace.branch}  |  commit ${workspace.shortSha}" +
                if (workspace.commitTitle.isNotBlank()) "\n${workspace.commitTitle}" else "",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onOpenEditor) { Text("Files") }
            TextButton(onClick = onOpenAgent) { Text("Agent") }
            TextButton(onClick = onOpenSettings) { Text("AI settings") }
            TextButton(onClick = onAskClear) { Text("Clear chat") }
        }
        val newer = sync.newerCommit
        when {
            sync.syncing -> {
                Text("Updating from GitHub...", style = MaterialTheme.typography.bodyMedium)
                val f = sync.progress?.fraction
                if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            newer != null -> {
                Text("Newer commit on GitHub: ${newer.shortSha} ${newer.title}", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onAskSync) { Text("Update local copy") }
            }
            else -> OutlinedButton(onClick = vm::checkForUpdates, enabled = !sync.checking) { Text(if (sync.checking) "Checking..." else "Check for updates") }
        }
    }
}

@Composable
private fun StageBar(state: WorkspaceUi, onApprove: () -> Unit, onStartAgent: () -> Unit) {
    when (state.stage) {
        WorkspaceStage.PLAN_READY -> Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Plan ready. No file has been changed.", style = MaterialTheme.typography.titleSmall)
                BodyText("Approve it, or write what you want changed and the AI will update the plan.")
                Button(onClick = onApprove, enabled = !state.busy) { Text("Approve plan") }
            }
        }
        WorkspaceStage.APPROVED -> Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Plan approved.", style = MaterialTheme.typography.titleSmall)
                BodyText("The Agent will prepare the detailed steps and ask once more before it prepares changes. Every change is shown for your review before any file is written.")
                Button(onClick = onStartAgent, enabled = !state.startingAgent) { Text(if (state.startingAgent) "Starting..." else "Start with Agent") }
            }
        }
        WorkspaceStage.DISCUSSING -> Unit
    }
}

@Composable
private fun TurnBubble(turn: ChatTurn, isLast: Boolean, busy: Boolean, onCopy: (String) -> Unit, onRetry: () -> Unit) {
    val isUser = turn.role == AiRole.USER
    val container = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Card(
        modifier = Modifier.fillMaxWidth().padding(start = if (isUser) 32.dp else 0.dp, end = if (isUser) 0.dp else 32.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (isUser) {
                Text(if (turn.text == WorkspacePlans.APPROVAL_TEXT) "Plan approved by you." else turn.text, style = MaterialTheme.typography.bodyMedium)
            } else {
                val parts = WorkspacePlans.split(turn.text)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (turn.state == TurnState.STREAMING && turn.text.isEmpty()) Text("Reading the project...", style = MaterialTheme.typography.bodySmall)
                        AnswerText(parts.before, onCopy)
                        parts.plan?.let { plan ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Plan", style = MaterialTheme.typography.titleSmall)
                                    AnswerText(plan, onCopy)
                                }
                            }
                        }
                        AnswerText(parts.after, onCopy)
                    }
                }
                when (turn.state) {
                    TurnState.ERROR -> Text(turn.error ?: "The request failed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TurnState.CANCELLED -> Text("Stopped.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Unit
                }
                if (turn.state != TurnState.STREAMING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (turn.text.isNotBlank()) TextButton(onClick = { onCopy(turn.text) }) { Text("Copy") }
                        if (isLast && !busy && turn.state != TurnState.DONE) TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnswerText(text: String, onCopy: (String) -> Unit) {
    if (text.isBlank()) return
    ChatMarkdown.split(text).forEach { block ->
        when (block) {
            is ChatMarkdown.Block.Text -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
            is ChatMarkdown.Block.Code -> CodeBlockView(
                code = block.code,
                language = ChatMarkdown.languageFor(block.language, block.path),
                onCopy = onCopy,
                title = block.path,
            )
        }
    }
}
