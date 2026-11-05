package com.almus.appcreatorpro.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.components.BuildCard
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.PrototypeBadge
import com.almus.appcreatorpro.ui.components.ProjectCard
import com.almus.appcreatorpro.ui.components.SectionHeader
import com.almus.appcreatorpro.ui.projects.CreateProjectDialog
import com.almus.appcreatorpro.ui.theme.Indigo40
import com.almus.appcreatorpro.ui.theme.Violet40
import com.almus.appcreatorpro.util.Formatters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenProjects: () -> Unit,
    onOpenProject: (String) -> Unit,
    onOpenBuildHistory: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenCloudBuild: (String) -> Unit,
    bottomBar: @Composable () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Dashboard") }) },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when (val current = state) {
                UiState.Loading -> LoadingView(message = "Loading dashboard...")
                is UiState.Error -> ErrorView(message = current.message, onRetry = viewModel::load)
                is UiState.Success -> HomeContent(
                    data = current.data,
                    onCreateProject = { showCreateDialog = true },
                    onOpenProjects = onOpenProjects,
                    onOpenBuildHistory = onOpenBuildHistory,
                    onProjectClick = { onOpenProject(it.id) },
                    onConnectGitHub = onOpenGitHub,
                    onStartCloudBuild = {
                        val target = current.data.recentProjects.firstOrNull()
                        if (target == null) viewModel.startCloudBuild() else onOpenCloudBuild(target.id)
                    },
                )
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
}

@Composable
private fun HomeContent(
    data: HomeData,
    onCreateProject: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenBuildHistory: () -> Unit,
    onProjectClick: (Project) -> Unit,
    onConnectGitHub: () -> Unit,
    onStartCloudBuild: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { HeroCard() }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onCreateProject, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("New project")
                }
                FilledTonalButton(onClick = onOpenProjects, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Open project")
                }
            }
        }

        item {
            SectionHeader(
                title = "Recent projects",
                trailing = { TextButton(onClick = onOpenProjects) { Text("See all") } },
            )
        }
        if (data.recentProjects.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "No projects yet",
                    message = "Create your first project to see it here.",
                    actionLabel = "Create project",
                    onAction = onCreateProject,
                )
            }
        } else {
            items(data.recentProjects.size) { index ->
                val project = data.recentProjects[index]
                ProjectCard(project = project, nowMillis = now, onClick = { onProjectClick(project) })
            }
        }

        item { GitHubCard(connection = data.gitHub, onConnect = onConnectGitHub) }

        item {
            CloudBuildCard(
                serviceName = data.cloudBuildName,
                available = data.cloudBuildAvailable,
                onStart = onStartCloudBuild,
            )
        }

        item {
            SectionHeader(
                title = "Build history",
                badge = if (data.recentBuilds.any { it.isSample }) "SAMPLE DATA" else null,
                trailing = { TextButton(onClick = onOpenBuildHistory) { Text("See all") } },
            )
        }
        if (data.recentBuilds.isEmpty()) {
            item {
                EmptyView(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "No builds yet",
                    message = "Builds you start will appear here.",
                )
            }
        } else {
            items(data.recentBuilds.size) { index ->
                BuildCard(build = data.recentBuilds[index], nowMillis = now)
            }
        }

        item { StorageCard(data = data) }
    }
}

@Composable
private fun HeroCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Indigo40, Violet40)))
            .padding(20.dp),
    ) {
        Column {
            Text(
                text = "Build Android apps from your phone",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Create projects and edit code on this device, then build them as APKs on GitHub Actions.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    badge: String?,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                if (badge != null) PrototypeBadge(label = badge)
            }
            content()
        }
    }
}

@Composable
private fun GitHubCard(connection: GitHubConnection, onConnect: () -> Unit) {
    InfoCard(title = "GitHub connection", badge = null) {
        val status = when (connection) {
            GitHubConnection.NotConnected -> "Not connected"
            is GitHubConnection.Connected -> "Connected as ${connection.username}"
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onConnect) {
            Text(if (connection is GitHubConnection.Connected) "Manage GitHub" else "Connect GitHub")
        }
    }
}

@Composable
private fun CloudBuildCard(serviceName: String, available: Boolean, onStart: () -> Unit) {
    InfoCard(title = "Cloud build", badge = null) {
        Text(
            text = serviceName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!available) {
            Text(
                text = "Connect GitHub first. The button opens the cloud build setup for your latest project.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onStart) { Text("Cloud build") }
    }
}

@Composable
private fun StorageCard(data: HomeData) {
    InfoCard(title = "Storage and projects", badge = null) {
        val storage = data.storage
        if (storage.totalBytes <= 0L) {
            Text(
                text = "Storage information is not available on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                progress = { storage.usedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Text(
                text = "${Formatters.formatBytes(storage.usedBytes)} used of ${Formatters.formatBytes(storage.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Free: ${Formatters.formatBytes(storage.freeBytes)}  |  App data: ${Formatters.formatBytes(storage.appDataBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "Projects: ${data.projectCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
