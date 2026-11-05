package com.almus.appcreatorpro.ui.transfer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.transfer.ImportPreview
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    onBack: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenGitHub: () -> Unit,
    viewModel: ImportViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val github by viewModel.githubState.collectAsStateWithLifecycle()

    // The system pickers need no storage permission: Android only grants access to what the user picks.
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) viewModel.importZip(uri.toString())
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) viewModel.importFolder(uri.toString())
    }

    BackHandler(enabled = state !is ImportUiState.Idle) { viewModel.back() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import project") },
                navigationIcon = {
                    IconButton(onClick = { if (!viewModel.back()) onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                ImportUiState.Idle -> SourcePicker(
                    onZip = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    onFolder = { folderPicker.launch(null) },
                    onGitHub = viewModel::openGitHub,
                )
                ImportUiState.GitHubBrowse -> GitHubPane(viewModel, github, onOpenGitHub)
                is ImportUiState.Working -> ScrollBody {
                    SectionCard(title = current.title) {
                        TransferProgressBlock(current.progress)
                        if (!current.saving) {
                            OutlinedButton(onClick = viewModel::cancel) { Text("Cancel") }
                        }
                    }
                }
                is ImportUiState.Preview -> ScrollBody {
                    PreviewCard(
                        preview = current.preview,
                        name = current.name,
                        nameError = current.nameError,
                        onNameChange = viewModel::onNameChange,
                        onConfirm = viewModel::confirmImport,
                        onCancel = viewModel::reset,
                    )
                }
                is ImportUiState.Done -> ScrollBody {
                    SectionCard(title = "Project imported", tone = CardTone.Success) {
                        BodyText("\"${current.project.name}\" is ready: ${current.preview.totalFiles} files.")
                        Button(onClick = { onOpenProject(current.project.id) }) { Text("Open project") }
                        OutlinedButton(onClick = viewModel::reset) { Text("Import another") }
                    }
                }
                is ImportUiState.Failed -> ScrollBody {
                    SectionCard(title = "Import failed", tone = CardTone.Error) {
                        Text(current.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        BodyText("Nothing was imported and your existing projects were not changed.")
                        Button(onClick = viewModel::reset) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScrollBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun SourcePicker(onZip: () -> Unit, onFolder: () -> Unit, onGitHub: () -> Unit) {
    ScrollBody {
        SectionCard(title = "ZIP file") {
            BodyText("Import a project from a .zip file on this device (Android Kotlin or Java, HTML/CSS/JavaScript).")
            Button(onClick = onZip) { Text("Choose ZIP file") }
        }
        SectionCard(title = "Folder or Android Studio project") {
            BodyText("Pick a project folder, for example an Android Studio project saved on your device.")
            Button(onClick = onFolder) { Text("Choose folder") }
        }
        SectionCard(title = "GitHub repository") {
            BodyText("Download a repository branch using your connected GitHub account.")
            Button(onClick = onGitHub) { Text("Browse repositories") }
        }
        BodyText("Nothing is saved until you confirm on the next screen. Build folders, .git and signing keys are left out.")
    }
}

@Composable
private fun PreviewCard(
    preview: ImportPreview,
    name: String,
    nameError: String?,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard(title = "Review before importing") {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("Project name") },
            singleLine = true,
            isError = nameError != null,
            supportingText = { Text(nameError.orEmpty()) },
            modifier = Modifier.fillMaxWidth(),
        )
        val type = preview.type
        BodyText("Source: ${preview.sourceKind.label} (${preview.sourceName})")
        BodyText("Type: ${type?.displayName ?: "Not recognized"}")
        if (preview.packageName.isNotBlank()) BodyText("Package: ${preview.packageName}")
        BodyText("${preview.editableFiles} editable files, ${preview.preservedFiles} preserved files, ${Formatters.formatBytes(preview.totalBytes)}")
        preview.reasons.forEach { BodyText("- $it") }
    }
    if (preview.problems.isNotEmpty()) {
        SectionCard(title = "Cannot import", tone = CardTone.Error) {
            preview.problems.forEach { Text(it, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
    if (preview.warnings.isNotEmpty()) {
        SectionCard(title = "Good to know") {
            preview.warnings.forEach { BodyText(it) }
            if (preview.preservedExamples.isNotEmpty()) BodyText("Preserved, for example: " + preview.preservedExamples.joinToString(", "))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onConfirm, enabled = preview.canImport && nameError == null && name.isNotBlank()) { Text("Import") }
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun GitHubPane(viewModel: ImportViewModel, github: GitHubImportState, onOpenGitHub: () -> Unit) {
    when {
        github.loading && github.repos.isEmpty() -> ScrollBody { SectionCard(title = "Loading repositories") { TransferProgressBlock(null) } }
        github.connected == false -> ScrollBody {
            SectionCard(title = "GitHub is not connected") {
                BodyText("Connect your GitHub account first, then come back to import a repository.")
                Button(onClick = onOpenGitHub) { Text("Connect GitHub") }
            }
        }
        github.selected != null -> BranchPane(viewModel, github)
        else -> RepoList(viewModel, github)
    }
}

@Composable
private fun RepoList(viewModel: ImportViewModel, github: GitHubImportState) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = github.query,
            onValueChange = viewModel::onRepositoryQuery,
            label = { Text("Search repositories") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        github.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(github.visibleRepos, key = { it.id }) { repo -> RepoRow(repo) { viewModel.selectRepository(repo) } }
            if (github.nextPage != null) {
                item { TextButton(onClick = viewModel::loadMoreRepositories, enabled = !github.loadingMore) { Text(if (github.loadingMore) "Loading..." else "Load more") } }
            }
            if (github.visibleRepos.isEmpty() && !github.loading) {
                item { BodyText("No repositories found.") }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: GitHubRepo, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(repo.fullName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val info = (if (repo.isPrivate) "Private" else "Public") + " - default branch " + repo.defaultBranch
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            repo.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun BranchPane(viewModel: ImportViewModel, github: GitHubImportState) {
    val repo = github.selected ?: return
    ScrollBody {
        SectionCard(title = repo.fullName) {
            BodyText("Choose the branch to import.")
            if (github.branchesLoading) TransferProgressBlock(null)
            github.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            github.branches.forEach { branch ->
                val selected = branch.name == github.selectedBranch
                if (selected) {
                    Button(onClick = { viewModel.selectBranch(branch.name) }, modifier = Modifier.fillMaxWidth()) { Text(branch.name) }
                } else {
                    OutlinedButton(onClick = { viewModel.selectBranch(branch.name) }, modifier = Modifier.fillMaxWidth()) { Text(branch.name) }
                }
            }
            if (!github.branchesLoading && github.branches.isEmpty()) BodyText("Using the default branch: ${repo.defaultBranch}.")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::importSelectedRepository, enabled = !github.branchesLoading) { Text("Download and review") }
            OutlinedButton(onClick = viewModel::clearSelectedRepository) { Text("Back") }
        }
    }
}
