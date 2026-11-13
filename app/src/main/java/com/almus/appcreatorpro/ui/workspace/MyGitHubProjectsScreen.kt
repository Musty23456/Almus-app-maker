package com.almus.appcreatorpro.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.util.Formatters
import com.almus.appcreatorpro.workspace.RepoWorkspace

/** "My GitHub Projects": repositories, search, repository information, import, and the workspaces already on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyGitHubProjectsScreen(
    onBack: () -> Unit,
    onOpenWorkspace: (workspaceId: String) -> Unit,
    onOpenGitHub: () -> Unit,
    viewModel: MyGitHubProjectsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    // Back from the repository page returns to the list; back while downloading cancels the download.
    BackHandler(enabled = state.detail != null && state.importing == null) { viewModel.closeDetail() }
    BackHandler(enabled = state.importing != null) { viewModel.cancelImport() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.repo?.fullName ?: "My GitHub Projects", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (state.detail != null && state.importing == null) viewModel.closeDetail() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.detail == null) {
                        IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val importing = state.importing
            val detail = state.detail
            when {
                importing != null -> ImportingPane(importing, onCancel = viewModel::cancelImport)
                detail != null -> RepoDetailPane(detail, viewModel, onOpenWorkspace)
                state.checking -> LoadingView(message = "Checking GitHub...")
                else -> ProjectsListPane(state, viewModel, onOpenWorkspace, onOpenGitHub)
            }
        }
    }
}

@Composable
private fun ImportingPane(importing: ImportingUi, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(importing.title, style = MaterialTheme.typography.titleMedium)
        val fraction = importing.progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
        }
        importing.progress?.let { p ->
            if (p.doneBytes > 0L) BodyText(Formatters.formatBytes(p.doneBytes) + (p.totalBytes?.let { " of " + Formatters.formatBytes(it) } ?: ""), Modifier.padding(top = 8.dp))
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 24.dp)) { Text("Cancel") }
    }
}

@Composable
private fun ProjectsListPane(
    state: MyGitHubProjectsUi,
    viewModel: MyGitHubProjectsViewModel,
    onOpenWorkspace: (String) -> Unit,
    onOpenGitHub: () -> Unit,
) {
    var forgetting by remember { mutableStateOf<RepoWorkspace?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = state.tab.ordinal) {
            ProjectsTab.entries.forEach { tab ->
                Tab(
                    selected = state.tab == tab,
                    onClick = { viewModel.setTab(tab) },
                    text = { Text(if (tab == ProjectsTab.WORKSPACES) "Workspaces (${state.workspaces.size})" else tab.label) },
                )
            }
        }
        when (state.tab) {
            ProjectsTab.REPOSITORIES -> RepositoriesTab(state, viewModel, onOpenWorkspace, onOpenGitHub)
            ProjectsTab.WORKSPACES -> WorkspacesTab(state, onOpen = { w -> viewModel.openWorkspace(w, onOpenWorkspace) }, onForget = { forgetting = it })
        }
    }

    forgetting?.let { w ->
        ConfirmDialog(
            title = "Remove workspace?",
            message = "${w.fullName} will be removed from this list and its AI conversation deleted. The project and its files stay in Projects.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { forgetting = null; viewModel.forgetWorkspace(w) },
            onDismiss = { forgetting = null },
        )
    }
}

@Composable
private fun RepositoriesTab(
    state: MyGitHubProjectsUi,
    viewModel: MyGitHubProjectsViewModel,
    onOpenWorkspace: (String) -> Unit,
    onOpenGitHub: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.login != null) {
                    BodyText("Signed in as ${state.login}. Your private and public repositories are listed below.")
                } else {
                    SectionCard("Public repositories only") {
                        BodyText("You can search public repositories or paste a link (owner/repo) without signing in. Connect GitHub to see and import your private repositories.")
                        OutlinedButton(onClick = onOpenGitHub) { Text("Connect GitHub") }
                    }
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text("Filter, search GitHub, or paste owner/repo") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = viewModel::searchGitHub, enabled = !state.searching && state.query.trim().length >= 2, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.searching) "Searching..." else "Search GitHub")
                }
                if (state.searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        state.error?.let { error ->
            item {
                SectionCard("Problem", tone = CardTone.Error) {
                    BodyText(error)
                    Button(onClick = viewModel::refresh) { Text("Try again") }
                }
            }
        }

        val results = state.searchResults
        if (results != null) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("GitHub results for \"${state.searchedText}\"", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearSearch) { Text("Clear") }
                }
            }
            if (results.isEmpty()) item { BodyText("GitHub found no repositories for this search.") }
            items(count = results.size) { i -> RepoRow(results[i], onClick = { viewModel.openRepo(results[i]) }) }
            if (state.searchNextPage != null) {
                item {
                    OutlinedButton(onClick = viewModel::loadMoreSearch, enabled = !state.searching, modifier = Modifier.fillMaxWidth()) { Text("More results") }
                }
            }
        } else {
            // Recent repository history: the workspaces opened last.
            val recent = if (state.query.isBlank()) state.workspaces.take(3) else emptyList()
            if (recent.isNotEmpty()) {
                item { Text("Recent", style = MaterialTheme.typography.titleSmall) }
                items(count = recent.size) { i ->
                    WorkspaceRow(
                        workspace = recent[i],
                        missing = recent[i].id in state.missingProjects,
                        onOpen = { viewModel.openWorkspace(recent[i], onOpenWorkspace) },
                        onForget = null,
                    )
                }
            }
            if (state.login != null) {
                item { Text("My repositories", style = MaterialTheme.typography.titleSmall) }
                val visible = state.visibleMine
                if (state.loadingMine) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                else if (visible.isEmpty() && state.error == null) {
                    item {
                        BodyText(if (state.myRepos.isEmpty()) "No repositories are available to this sign-in." else "No repository matches. Tap \"Search GitHub\" to look further.")
                    }
                }
                items(count = visible.size) { i -> RepoRow(visible[i], onClick = { viewModel.openRepo(visible[i]) }) }
                if (state.myNextPage != null) {
                    item {
                        OutlinedButton(onClick = viewModel::loadMoreMine, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.loadingMore) "Loading..." else "Load more")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspacesTab(
    state: MyGitHubProjectsUi,
    onOpen: (RepoWorkspace) -> Unit,
    onForget: (RepoWorkspace) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.workspaces.isEmpty()) {
            item { BodyText("No workspaces yet. Pick a repository on the Repositories tab and import it.") }
        }
        items(count = state.workspaces.size) { i ->
            val w = state.workspaces[i]
            WorkspaceRow(workspace = w, missing = w.id in state.missingProjects, onOpen = { onOpen(w) }, onForget = { onForget(w) })
        }
    }
}

@Composable
private fun WorkspaceRow(workspace: RepoWorkspace, missing: Boolean, onOpen: () -> Unit, onForget: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (workspace.isPrivate) Icon(Icons.Filled.Lock, contentDescription = "Private", modifier = Modifier.padding(end = 2.dp))
                Text(workspace.fullName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BodyText("branch ${workspace.branch}  |  commit ${workspace.shortSha}  |  opened ${Formatters.relativeTime(System.currentTimeMillis(), workspace.lastOpenedAtMillis)}")
            if (workspace.commitTitle.isNotBlank()) Text(workspace.commitTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (missing) {
                Text("The local copy was deleted. Import it again from the Repositories tab.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, enabled = !missing) { Text("Open") }
                if (onForget != null) TextButton(onClick = onForget) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: GitHubRepo, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(repo.fullName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(
                    if (repo.isPrivate) "Private" else "Public",
                    repo.language,
                    repo.stars?.let { "$it stars" },
                    "default branch: ${repo.defaultBranch}",
                ).joinToString("  |  "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RepoDetailPane(detail: RepoDetailUi, viewModel: MyGitHubProjectsViewModel, onOpenWorkspace: (String) -> Unit) {
    var branchMenu by remember { mutableStateOf(false) }
    val repo = detail.repo
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Repository information") {
            repo.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            BodyText(
                listOfNotNull(
                    if (repo.isPrivate) "Private repository" else "Public repository",
                    repo.language?.let { "Language: $it" },
                    repo.stars?.let { "Stars: $it" },
                    repo.sizeKb?.let { "Size: " + Formatters.formatBytes(it * 1024L) },
                    repo.updatedAtIso?.let { "Last push: " + it.substringBefore('T') },
                    "Default branch: ${repo.defaultBranch}",
                    "Branches: " + (if (detail.loading && detail.branches.isEmpty()) "loading..." else detail.branches.size.toString()),
                ).joinToString("\n"),
            )
            BodyText(repo.htmlUrl)
        }

        SectionCard("Branch") {
            Box {
                OutlinedButton(onClick = { branchMenu = true }, enabled = detail.branches.size > 1, modifier = Modifier.fillMaxWidth()) {
                    Text(detail.selectedBranch, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = branchMenu, onDismissRequest = { branchMenu = false }) {
                    detail.branches.forEach { b ->
                        DropdownMenuItem(
                            text = { Text(b.name + if (b.name == repo.defaultBranch) "  (default)" else "") },
                            onClick = { branchMenu = false; viewModel.selectBranch(b.name) },
                        )
                    }
                }
            }
            if (detail.branches.size >= 100) BodyText("Showing the first 100 branches.")
        }

        SectionCard("Last commit on \"${detail.selectedBranch}\"") {
            val commit = detail.latestCommit
            when {
                commit != null -> {
                    Text("${commit.shortSha}  ${commit.title}", style = MaterialTheme.typography.bodyLarge)
                    BodyText(listOfNotNull(commit.author, commit.dateIso?.replace('T', ' ')?.removeSuffix("Z")).joinToString("  |  "))
                }
                detail.loading -> BodyText("Loading...")
                else -> BodyText("Not available.")
            }
        }

        detail.error?.let { error ->
            SectionCard("Problem", tone = CardTone.Error) { BodyText(error) }
        }

        val existing = detail.existing
        Button(
            onClick = { viewModel.importSelected(onOpenWorkspace) },
            enabled = !detail.loading && detail.latestCommit != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (existing != null) "Open workspace" else "Import and open workspace") }
        BodyText(
            if (existing != null) {
                "This branch is already on your phone (commit ${existing.shortSha}). Opening it does not download again and keeps your local changes; use Sync inside the workspace to update it."
            } else {
                "Downloads this branch's newest commit into a new local project and opens an AI conversation about it. " +
                    if (repo.isPrivate) "Your encrypted GitHub sign-in is used for this private repository; the access token is never shown or copied into the workspace."
                    else "No files are changed on GitHub."
            },
        )
    }
}
