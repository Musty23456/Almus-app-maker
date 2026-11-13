package com.almus.appcreatorpro.ui.git

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.git.ChangeKind
import com.almus.appcreatorpro.git.ConflictSide
import com.almus.appcreatorpro.git.GitFileChange
import com.almus.appcreatorpro.git.GitRepoState
import com.almus.appcreatorpro.git.GitRiskLevel
import com.almus.appcreatorpro.ui.AppViewModelProvider
import java.text.DateFormat
import java.util.Date

private sealed interface GitDialog {
    data object Commit : GitDialog
    data object NewBranch : GitDialog
    data object Remote : GitDialog
    data object Identity : GitDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit = {},
    onOpenGitHub: () -> Unit = {},
) {
    val vm: GitViewModel = viewModel(key = "git-$projectId", factory = AppViewModelProvider.gitFactory(projectId))
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<GitDialog?>(null) }

    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.messageShown() } }
    LaunchedEffect(s.error) { s.error?.let { snackbar.showSnackbar(it); vm.errorShown() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Git") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = vm::refresh, enabled = s.busy == null) { Text("Refresh") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (s.loading) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                return@Column
            }
            if (!s.hasRepo) {
                NoRepository(onInit = vm::initRepository, busy = s.busy != null)
                return@Column
            }
            val st = s.status!!
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (st.branch != null) "Branch: ${st.branch}" else "Not on a branch (detached)", style = MaterialTheme.typography.titleMedium)
                    val sync = when {
                        st.upstream == null -> "No upstream branch yet"
                        st.ahead == 0 && st.behind == 0 -> "Up to date with ${st.upstream}"
                        else -> "${st.ahead} ahead, ${st.behind} behind ${st.upstream}"
                    }
                    Text(sync, style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            st.state == GitRepoState.MERGING -> "Merge in progress"
                            st.isClean -> "Working tree clean"
                            else -> "${st.staged.size} staged, ${st.unstaged.size} modified, ${st.untracked.size} new, ${st.conflicted.size} conflicted"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.busy != null) {
                        val p = s.progress
                        Text(p?.task?.takeIf { it.isNotBlank() } ?: s.busy.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        if (p != null && p.total > 0) LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        TextButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Changes", "History", "Branches", "Remote").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            val busy = s.busy != null
            when (tab) {
                0 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (st.state == GitRepoState.MERGING) item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Merge in progress", style = MaterialTheme.typography.titleSmall)
                                Text("Fix every conflicted file, mark it resolved, then commit. Or abort the merge.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = vm::abortMerge, enabled = !busy) { Text("Abort merge") }
                            }
                        }
                    }
                    if (s.conflicts.isNotEmpty()) {
                        item { Text("Conflicts", style = MaterialTheme.typography.titleSmall) }
                        items(s.conflicts, key = { "c-" + it.path }) { c ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(c.path, style = MaterialTheme.typography.bodyMedium)
                                    Text(if (c.stillHasMarkers) "${c.regions} conflict region(s) left" else "No conflict markers left", style = MaterialTheme.typography.bodySmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { onOpenFile(c.path) }) { Text("Open") }
                                        TextButton(onClick = { vm.markResolved(c.path) }, enabled = !busy) { Text("Resolved") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { vm.resolveWithSide(c.path, ConflictSide.OURS) }, enabled = !busy) { Text("Keep mine") }
                                        TextButton(onClick = { vm.resolveWithSide(c.path, ConflictSide.THEIRS) }, enabled = !busy) { Text("Take theirs") }
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::stageAll, enabled = !busy) { Text("Stage all") }
                            OutlinedButton(onClick = vm::unstageAll, enabled = !busy && st.staged.isNotEmpty()) { Text("Unstage all") }
                            Button(onClick = { dialog = GitDialog.Commit }, enabled = !busy && (st.staged.isNotEmpty() || st.state == GitRepoState.MERGING) && st.conflicted.isEmpty()) { Text("Commit") }
                        }
                    }
                    section("Staged", st.staged, "Unstage", { vm.unstage(it) }, onOpenFile, !busy) { it.staged }
                    section("Modified", st.unstaged, "Stage", { vm.stage(it) }, onOpenFile, !busy) { it.unstaged }
                    section("New files", st.untracked, "Stage", { vm.stage(it) }, onOpenFile, !busy) { it.unstaged }
                    if (st.isClean) item { Text("Nothing to commit.", style = MaterialTheme.typography.bodyMedium) }
                }
                1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (s.history.isEmpty()) item { Text("No commits yet.", style = MaterialTheme.typography.bodyMedium) }
                    items(s.history, key = { it.sha }) { c ->
                        Card(Modifier.fillMaxWidth().clickable { vm.openCommit(c) }) {
                            Column(Modifier.padding(10.dp)) {
                                Text(c.summary.ifBlank { "(no message)" }, style = MaterialTheme.typography.bodyMedium)
                                Text("${c.shortSha}  ${c.authorName}  ${formatTime(c.timeMillis)}" + if (c.isMerge) "  merge" else "", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (s.historyHasMore) item { TextButton(onClick = vm::loadMoreHistory) { Text("Load more") } }
                }
                2 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { Button(onClick = { dialog = GitDialog.NewBranch }, enabled = !busy && st.hasCommits) { Text("New branch") } }
                    if (!st.hasCommits) item { Text("Make a first commit before creating branches.", style = MaterialTheme.typography.bodySmall) }
                    items(s.branches, key = { (if (it.isRemote) "r-" else "l-") + it.name }) { b ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text((if (b.isCurrent) "● " else "") + b.name + if (b.isRemote) "  (remote)" else "", style = MaterialTheme.typography.bodyMedium)
                                if (b.upstream != null) Text("tracks ${b.upstream}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (!b.isCurrent) TextButton(onClick = { vm.switchBranch(b.name) }, enabled = !busy) { Text("Switch") }
                                    if (!b.isCurrent) TextButton(onClick = { vm.merge(b.name) }, enabled = !busy) { Text("Merge into current") }
                                    if (!b.isCurrent && !b.isRemote) TextButton(onClick = { vm.deleteBranch(b.name) }, enabled = !busy) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("GitHub sign-in", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (s.githubConnected) "Connected. Your sign-in is used only for github.com addresses and is never stored in the repository."
                                    else "Not connected. Public repositories work without it; private ones and pushing need it.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (!s.githubConnected) TextButton(onClick = onOpenGitHub) { Text("Connect GitHub") }
                            }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::fetch, enabled = !busy && s.remotes.isNotEmpty()) { Text("Fetch") }
                            OutlinedButton(onClick = vm::pull, enabled = !busy && s.remotes.isNotEmpty()) { Text("Pull") }
                            Button(onClick = vm::push, enabled = !busy && s.remotes.isNotEmpty() && st.hasCommits) { Text("Push") }
                        }
                    }
                    items(s.remotes, key = { it.name }) { r ->
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Text(r.name, style = MaterialTheme.typography.bodyMedium); Text(r.url, style = MaterialTheme.typography.bodySmall) } }
                    }
                    item { OutlinedButton(onClick = { dialog = GitDialog.Remote }, enabled = !busy) { Text(if (s.remotes.isEmpty()) "Add remote" else "Change remote") } }
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Commit author", style = MaterialTheme.typography.titleSmall)
                                Text(s.identity?.let { "${it.name} <${it.email}>" } ?: "Not set yet", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { dialog = GitDialog.Identity }) { Text("Edit") }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- dialogs
    when (dialog) {
        GitDialog.Commit -> TextDialog("Commit staged changes", listOf("Message" to ""), "Commit", onDismiss = { dialog = null }) { (m) ->
            if (s.identity == null) dialog = GitDialog.Identity else { dialog = null; vm.commit(m) }
        }
        GitDialog.NewBranch -> TextDialog("New branch", listOf("Branch name" to ""), "Create and switch", onDismiss = { dialog = null }) { (n) -> dialog = null; vm.createBranch(n) }
        GitDialog.Remote -> TextDialog("Remote repository", listOf("Name" to "origin", "https:// address" to s.remotes.firstOrNull()?.url.orEmpty()), "Save", onDismiss = { dialog = null }) { (n, u) -> dialog = null; vm.setRemote(n, u) }
        GitDialog.Identity -> TextDialog("Commit author", listOf("Name" to (s.identity?.name ?: ""), "Email" to (s.identity?.email ?: "")), "Save", onDismiss = { dialog = null }) { (n, e) -> dialog = null; vm.saveIdentity(n, e) }
        null -> Unit
    }

    s.pendingRisk?.let { pending ->
        var typed by remember(pending) { mutableStateOf("") }
        val needsText = pending.risk.level == GitRiskLevel.TYPE_TO_CONFIRM
        AlertDialog(
            onDismissRequest = vm::dismissRisk,
            title = { Text(pending.risk.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.risk.message)
                    if (needsText) OutlinedTextField(value = typed, onValueChange = { typed = it }, label = { Text("Type \"${pending.risk.requiredText}\"") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmRisk(typed) }, enabled = !needsText || typed.trim() == pending.risk.requiredText) { Text(pending.risk.confirmLabel) } },
            dismissButton = { TextButton(onClick = vm::dismissRisk) { Text("Cancel") } },
        )
    }

    s.openCommit?.let { (commit, files) ->
        AlertDialog(
            onDismissRequest = vm::closeCommit,
            title = { Text(commit.summary.ifBlank { commit.shortSha }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${commit.shortSha} · ${commit.authorName} · ${formatTime(commit.timeMillis)}", style = MaterialTheme.typography.bodySmall)
                    if (commit.message != commit.summary) Text(commit.message, style = MaterialTheme.typography.bodySmall)
                    Text("${files.size} changed file(s)", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(Modifier.padding(top = 4.dp)) { items(files) { f -> Text("${kindLabel(f.kind)}  ${f.path}", style = MaterialTheme.typography.bodySmall) } }
                }
            },
            confirmButton = { TextButton(onClick = vm::closeCommit) { Text("Close") } },
        )
    }
}

@Composable
private fun NoRepository(onInit: () -> Unit, busy: Boolean) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This project is not a Git repository yet.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Initializing keeps the history in a separate folder, so your project files stay exactly as they are. " +
                "A .gitignore that excludes build output and signing keys is added if the project has none.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onInit, enabled = !busy) { Text("Initialize repository") }
        Text("To copy an existing repository instead, use \"Clone repository\" on the Projects screen.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    list: List<GitFileChange>,
    action: String,
    onAction: (String) -> Unit,
    onOpen: (String) -> Unit,
    enabled: Boolean,
    kind: (GitFileChange) -> ChangeKind?,
) {
    if (list.isEmpty()) return
    item { Text("$title (${list.size})", style = MaterialTheme.typography.titleSmall) }
    items(list, key = { "$title-${it.path}" }) { f ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(kindLabel(kind(f)), Modifier.padding(end = 8.dp), style = MaterialTheme.typography.labelMedium)
            Text(f.path, Modifier.weight(1f).clickable { onOpen(f.path) }, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onAction(f.path) }, enabled = enabled) { Text(action) }
        }
    }
}

private fun kindLabel(k: ChangeKind?): String = when (k) {
    ChangeKind.ADDED -> "A"
    ChangeKind.MODIFIED -> "M"
    ChangeKind.DELETED -> "D"
    ChangeKind.RENAMED -> "R"
    ChangeKind.UNTRACKED -> "?"
    null -> " "
}

private fun formatTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

@Composable
internal fun TextDialog(
    title: String,
    fields: List<Pair<String, String>>,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val values = remember(title) { mutableStateOf(fields.map { it.second }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { i, (label, _) ->
                    OutlinedTextField(
                        value = values.value[i],
                        onValueChange = { v -> values.value = values.value.toMutableList().also { it[i] = v } },
                        label = { Text(label) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(values.value) }, enabled = values.value.all { it.isNotBlank() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
