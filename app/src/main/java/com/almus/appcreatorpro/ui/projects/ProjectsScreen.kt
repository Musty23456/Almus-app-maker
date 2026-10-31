package com.almus.appcreatorpro.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.ProjectCard
import com.almus.appcreatorpro.ui.components.TextInputDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(
    onOpenProject: (String) -> Unit,
    onCloudBuild: (String) -> Unit,
    bottomBar: @Composable () -> Unit,
    viewModel: ProjectsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val issues by viewModel.loadIssues.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }
    var hideIssues by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Projects") }) },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Create project")
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (val current = state) {
                UiState.Loading -> LoadingView(message = "Loading projects...")
                is UiState.Error -> ErrorView(message = current.message, onRetry = viewModel::load)
                is UiState.Success -> {
                    val now = remember(current.data) { System.currentTimeMillis() }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (issues.isNotEmpty() && !hideIssues) {
                            item { IssuesBanner(issues = issues, onHide = { hideIssues = true }) }
                        }
                        if (current.data.isEmpty()) {
                            item {
                                EmptyView(
                                    icon = Icons.AutoMirrored.Filled.List,
                                    title = "No projects yet",
                                    message = "Create a project to start coding. Projects are saved on this device.",
                                    actionLabel = "Create project",
                                    onAction = { showCreateDialog = true },
                                )
                            }
                        }
                        items(current.data.size) { index ->
                            val project = current.data[index]
                            ProjectCard(
                                project = project,
                                nowMillis = now,
                                onClick = { onOpenProject(project.id) },
                                trailing = {
                                    ProjectMenu(
                                        onOpen = { onOpenProject(project.id) },
                                        onCloudBuild = { onCloudBuild(project.id) },
                                        onRename = { renameTarget = project },
                                        onDelete = { deleteTarget = project },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreated = { project ->
                showCreateDialog = false
                viewModel.onProjectCreated(project)
                onOpenProject(project.id)
            },
        )
    }

    renameTarget?.let { project ->
        val others = (state as? UiState.Success)?.data.orEmpty().filter { it.id != project.id }.map { it.name }
        TextInputDialog(
            title = "Rename project",
            label = "Project name",
            initialValue = project.name,
            confirmLabel = "Rename",
            validate = { ProjectNameValidator.validate(it, others).errorMessage },
            onConfirm = { newName ->
                renameTarget = null
                viewModel.renameProject(project, newName)
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { project ->
        ConfirmDialog(
            title = "Delete project?",
            message = "\"${project.name}\" and all of its files will be permanently deleted from this device. This cannot be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                deleteTarget = null
                viewModel.deleteProject(project)
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun ProjectMenu(onOpen: () -> Unit, onCloudBuild: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Project actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { expanded = false; onOpen() })
            DropdownMenuItem(text = { Text("Cloud build") }, onClick = { expanded = false; onCloudBuild() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun IssuesBanner(issues: List<String>, onHide: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "${issues.size} saved project(s) could not be loaded",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            issues.take(3).forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = "Your other projects are not affected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onHide) { Text("Hide") }
        }
    }
}
