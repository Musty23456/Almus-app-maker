package com.almus.appcreatorpro.ui.github

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.ui.components.TextInputDialog
import com.almus.appcreatorpro.domain.github.CloudBuildInputValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepositorySelectorScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenGitHub: () -> Unit,
    viewModel: RepositorySelectorViewModel = viewModel(
        key = "repos-$projectId",
        factory = AppViewModelProvider.repositorySelectorFactory(projectId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateRepo by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }
    // Back from the branch page returns to the list instead of leaving the screen.
    BackHandler(enabled = state.detail != null) { viewModel.closeDetail() }
    // Coming back from the GitHub connection screen: try again if we were not connected.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!state.connected && !state.loading) viewModel.load()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.detail?.repo?.fullName ?: "Choose repository") },
                navigationIcon = {
                    IconButton(onClick = { if (state.detail != null) viewModel.closeDetail() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            val detail = state.detail
            when {
                state.loading -> LoadingView(message = "Loading repositories...")
                !state.connected -> EmptyView(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "GitHub is not connected",
                    message = "Connect GitHub first. Repositories are only listed after GitHub confirms your sign-in.",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "Open GitHub connection",
                    onAction = onOpenGitHub,
                )
                detail != null -> RepoDetailPane(
                    detail = detail,
                    working = state.working,
                    viewModel = viewModel,
                    onDone = onBack,
                )
                else -> RepoListPane(state = state, viewModel = viewModel, onCreate = { showCreateRepo = true })
            }
        }
    }

    if (showCreateRepo) {
        CreateRepositoryDialog(
            canPrivate = state.access?.canCreatePrivateRepository == true,
            onDismiss = { showCreateRepo = false },
            onCreate = { name, description, isPrivate ->
                showCreateRepo = false
                viewModel.createRepository(name, description, isPrivate)
            },
        )
    }
}

@Composable
private fun RepoListPane(state: RepoSelectorState, viewModel: RepositorySelectorViewModel, onCreate: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BodyText(state.access?.describe().orEmpty())
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text("Search repositories") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.access?.canCreateRepository == true) {
                    OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Create a new repository") }
                } else {
                    BodyText("This sign-in cannot create repositories. Create one on github.com, then pull to refresh here.")
                }
                if (state.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        state.error?.let { error ->
            item {
                SectionCard("Could not load repositories", tone = CardTone.Error) {
                    BodyText(error)
                    Button(onClick = viewModel::load) { Text("Try again") }
                }
            }
        }
        val visible = state.visibleRepos
        if (visible.isEmpty() && state.error == null) {
            item {
                BodyText(
                    if (state.repos.isEmpty()) {
                        "No repositories are available to this sign-in. With a GitHub App, install the app on a repository first " +
                            "(github.com > your app > Install App)."
                    } else {
                        "No repository matches your search."
                    },
                )
            }
        }
        items(visible.size) { index ->
            RepoRow(visible[index], onClick = { viewModel.openRepo(visible[index]) })
        }
        if (state.nextPage != null) {
            item {
                OutlinedButton(onClick = viewModel::loadMore, enabled = !state.loadingMore, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.loadingMore) "Loading..." else "Load more")
                }
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
                text = listOf(
                    if (repo.isPrivate) "Private" else "Public",
                    "default branch: ${repo.defaultBranch}",
                    if (repo.canPush) null else "READ-ONLY (cannot upload)",
                ).filterNotNull().joinToString("  |  "),
                style = MaterialTheme.typography.bodySmall,
                color = if (repo.canPush) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun RepoDetailPane(detail: RepoDetail, working: Boolean, viewModel: RepositorySelectorViewModel, onDone: () -> Unit) {
    var showNewBranch by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Latest commit on \"${detail.selectedBranch}\"") {
            val commit = detail.latestCommit
            when {
                commit != null -> {
                    Text("${commit.shortSha}  ${commit.title}", style = MaterialTheme.typography.bodyLarge)
                    BodyText(listOfNotNull(commit.author, commit.dateIso).joinToString("  |  "))
                }
                detail.loading -> CircularProgressIndicator()
                else -> BodyText("Not available.")
            }
        }
        detail.error?.let { SectionCard("Problem", tone = CardTone.Error) { BodyText(it) } }
        SectionCard("Branch") {
            BodyText(
                "Almus commits your project files to this branch. Prefer a separate branch (for example almus-builds) " +
                    "so your main branch stays clean. The workflow file must exist on it and on the default branch.",
            )
            detail.branches.forEach { branch ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = detail.selectedBranch == branch.name, onClick = { viewModel.selectBranch(branch.name) }, role = Role.RadioButton)
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = detail.selectedBranch == branch.name, onClick = null)
                    Text(branch.name + if (branch.isProtected) "  (protected)" else "", style = MaterialTheme.typography.bodyLarge)
                }
            }
            OutlinedButton(onClick = { showNewBranch = true }, enabled = !working) { Text("New branch from ${detail.repo.defaultBranch}") }
        }
        Button(
            onClick = { viewModel.confirm(onDone) },
            enabled = !detail.loading && detail.error == null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Use ${detail.repo.fullName} (${detail.selectedBranch})") }
        TextButton(onClick = viewModel::closeDetail) { Text("Back to the list") }
    }
    if (showNewBranch) {
        TextInputDialog(
            title = "New branch",
            label = "Branch name",
            initialValue = "almus-builds",
            confirmLabel = "Create",
            validate = { CloudBuildInputValidator.validateBranchName(it) },
            onConfirm = {
                showNewBranch = false
                viewModel.createBranch(it)
            },
            onDismiss = { showNewBranch = false },
        )
    }
}

@Composable
private fun CreateRepositoryDialog(
    canPrivate: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, isPrivate: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("almus-builds") }
    var description by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(canPrivate) }
    val valid = Regex("^[A-Za-z0-9._-]{1,100}$").matches(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.trim() }, label = { Text("Name") }, singleLine = true, isError = !valid)
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") }, singleLine = true)
                if (canPrivate) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                        Text("Private repository")
                    }
                } else {
                    BodyText("This sign-in can only create PUBLIC repositories. Anyone can read what you upload.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, description, isPrivate && canPrivate) }, enabled = valid) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
