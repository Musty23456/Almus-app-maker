package com.almus.appcreatorpro.ui.home

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.components.ActionTile
import com.almus.appcreatorpro.ui.components.AlmusButton
import com.almus.appcreatorpro.ui.components.AlmusButtonStyle
import com.almus.appcreatorpro.ui.components.AlmusCard
import com.almus.appcreatorpro.ui.components.BuildCard
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.ProjectCard
import com.almus.appcreatorpro.ui.components.SectionHeader
import com.almus.appcreatorpro.ui.components.SkeletonBlock
import com.almus.appcreatorpro.ui.components.SkeletonList
import com.almus.appcreatorpro.ui.components.StatTile
import com.almus.appcreatorpro.ui.projects.CreateProjectDialog
import com.almus.appcreatorpro.ui.theme.BrandEnd
import com.almus.appcreatorpro.ui.theme.BrandStart
import com.almus.appcreatorpro.ui.theme.CodeTextStyle
import com.almus.appcreatorpro.ui.theme.Dimens
import com.almus.appcreatorpro.ui.theme.LayoutClass
import com.almus.appcreatorpro.ui.theme.Motion
import com.almus.appcreatorpro.ui.theme.Spacing
import com.almus.appcreatorpro.ui.theme.almus
import com.almus.appcreatorpro.ui.theme.gridColumnsFor
import com.almus.appcreatorpro.ui.theme.rememberLayoutClass
import com.almus.appcreatorpro.ui.theme.rememberReducedMotion
import com.almus.appcreatorpro.ui.theme.statColumnsFor
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
    onOpenGitHubProjects: () -> Unit = {},
    onGenerateWithAi: () -> Unit = {},
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
        topBar = {
            TopAppBar(
                title = { Text("Dashboard", modifier = Modifier.semantics { heading() }) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // On tablets and in landscape the content stays in a readable column in the middle.
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(modifier = Modifier.widthIn(max = Dimens.MaxContentWidth).fillMaxSize()) {
                when (val current = state) {
                    UiState.Loading -> DashboardSkeleton()
                    is UiState.Error -> ErrorView(message = current.message, onRetry = viewModel::load)
                    is UiState.Success -> HomeContent(
                        data = current.data,
                        onCreateProject = { showCreateDialog = true },
                        onOpenProjects = onOpenProjects,
                        onOpenBuildHistory = onOpenBuildHistory,
                        onProjectClick = { onOpenProject(it.id) },
                        onConnectGitHub = onOpenGitHub,
                        onOpenGitHubProjects = onOpenGitHubProjects,
                        onGenerateWithAi = onGenerateWithAi,
                        onStartCloudBuild = {
                            val target = current.data.recentProjects.firstOrNull()
                            if (target == null) viewModel.startCloudBuild() else onOpenCloudBuild(target.id)
                        },
                    )
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
}

/** Placeholder with the same shape as the real dashboard, so the screen does not jump when data arrives. */
@Composable
private fun DashboardSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        SkeletonBlock(modifier = Modifier.fillMaxWidth().height(132.dp), shape = MaterialTheme.shapes.extraLarge)
        SkeletonList(count = 3, label = "Loading dashboard")
    }
}

private data class QuickAction(
    val title: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val onClick: () -> Unit,
    val emphasized: Boolean = false,
)

@Composable
private fun HomeContent(
    data: HomeData,
    onCreateProject: () -> Unit,
    onOpenProjects: () -> Unit,
    onOpenBuildHistory: () -> Unit,
    onProjectClick: (Project) -> Unit,
    onConnectGitHub: () -> Unit,
    onOpenGitHubProjects: () -> Unit,
    onGenerateWithAi: () -> Unit,
    onStartCloudBuild: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val stats = remember(data.projectCount, data.recentBuilds) { DashboardStats.from(data.projectCount, data.recentBuilds) }
    val layout = rememberLayoutClass()
    val reduced = rememberReducedMotion()
    val fadeSpec: FiniteAnimationSpec<Float>? = if (reduced) null else tween(Motion.Standard)
    val moveSpec: FiniteAnimationSpec<IntOffset>? = if (reduced) null else tween(Motion.Standard)

    val actions = listOf(
        QuickAction("New project", "Start from a template", Icons.Filled.Add, onCreateProject, emphasized = true),
        QuickAction("Open project", "Continue where you left off", Icons.Filled.PlayArrow, onOpenProjects),
        QuickAction("GitHub projects", "Import a repo and ask the AI", Icons.Filled.Search, onOpenGitHubProjects),
        QuickAction("Generate with AI", "Describe the app you want", Icons.Filled.Star, onGenerateWithAi),
        QuickAction("Cloud build", "Build an APK on GitHub", Icons.Filled.Build, onStartCloudBuild),
        QuickAction("Build history", "Past builds and results", Icons.Filled.Refresh, onOpenBuildHistory),
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        item(key = "hero") { HeroCard(greeting = greetingForHour(hour), projectCount = data.projectCount) }

        item(key = "stats") { StatsGrid(stats = stats, data = data, layout = layout) }

        item(key = "actions_header") { SectionHeader(title = "Quick actions") }
        item(key = "actions") {
            TileGrid(columns = gridColumnsFor(layout), count = actions.size) { index, tileModifier ->
                val action = actions[index]
                ActionTile(
                    title = action.title,
                    subtitle = action.subtitle,
                    icon = action.icon,
                    onClick = action.onClick,
                    emphasized = action.emphasized,
                    modifier = tileModifier,
                )
            }
        }

        item(key = "recent_header") {
            SectionHeader(
                title = "Recent projects",
                trailing = { TextButton(onClick = onOpenProjects) { Text("See all") } },
            )
        }
        if (data.recentProjects.isEmpty()) {
            item(key = "recent_empty") {
                EmptyView(
                    icon = Icons.AutoMirrored.Filled.List,
                    title = "No projects yet",
                    message = "Create your first project to see it here.",
                    actionLabel = "Create project",
                    onAction = onCreateProject,
                )
            }
        } else {
            items(items = data.recentProjects, key = { "project_${it.id}" }) { project ->
                ProjectCard(
                    project = project,
                    nowMillis = now,
                    onClick = { onProjectClick(project) },
                    modifier = Modifier.animateItem(fadeInSpec = fadeSpec, placementSpec = moveSpec, fadeOutSpec = fadeSpec),
                )
            }
        }

        item(key = "connections_header") { SectionHeader(title = "Connections") }
        item(key = "github") { GitHubCard(connection = data.gitHub, onConnect = onConnectGitHub) }
        item(key = "cloud") {
            CloudBuildCard(
                serviceName = data.cloudBuildName,
                available = data.cloudBuildAvailable,
                onStart = onStartCloudBuild,
            )
        }

        item(key = "builds_header") {
            SectionHeader(
                title = "Build history",
                badge = if (data.recentBuilds.any { it.isSample }) "SAMPLE DATA" else null,
                trailing = { TextButton(onClick = onOpenBuildHistory) { Text("See all") } },
            )
        }
        if (data.recentBuilds.isEmpty()) {
            item(key = "builds_empty") {
                EmptyView(
                    icon = Icons.Filled.Build,
                    title = "No builds yet",
                    message = "Builds you start will appear here.",
                )
            }
        } else {
            // The index is part of the key: build ids come from several sources, and a repeated key would crash the list.
            itemsIndexed(items = data.recentBuilds, key = { index, build -> "build_${build.id}_$index" }) { _, build ->
                BuildCard(
                    build = build,
                    nowMillis = now,
                    modifier = Modifier.animateItem(fadeInSpec = fadeSpec, placementSpec = moveSpec, fadeOutSpec = fadeSpec),
                )
            }
        }

        item(key = "storage") { StorageCard(data = data) }
    }
}

@Composable
private fun HeroCard(greeting: String, projectCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(Brush.linearGradient(listOf(BrandStart, BrandEnd)))
            .padding(Spacing.xl),
    ) {
        // Decorative code glyph: gives the card an editor feel and is hidden from screen readers.
        Text(
            text = "</>",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clearAndSetSemantics { },
            style = CodeTextStyle.copy(fontSize = 40.sp),
            color = Color.White.copy(alpha = 0.18f),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(text = greeting, style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.92f))
            Text(
                text = "Build Android apps from your phone",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = if (projectCount == 0) {
                    "Create projects and edit code on this device, then build them as APKs on GitHub Actions."
                } else {
                    "Edit code on this device and build APKs on GitHub Actions. You have $projectCount " +
                        (if (projectCount == 1) "project." else "projects.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f),
            )
        }
    }
}

/** Equal-width tiles in rows of [columns]; the last row is padded so tiles keep the same width. */
@Composable
private fun TileGrid(
    columns: Int,
    count: Int,
    tile: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    val perRow = columns.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        (0 until count).chunked(perRow).forEach { rowIndexes ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                rowIndexes.forEach { index -> tile(index, Modifier.weight(1f).fillMaxHeight()) }
                repeat(perRow - rowIndexes.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: DashboardStats, data: HomeData, layout: LayoutClass) {
    val scheme = MaterialTheme.colorScheme
    val extras = MaterialTheme.almus
    val storage = data.storage
    TileGrid(columns = statColumnsFor(layout), count = 4) { index, tileModifier ->
        when (index) {
            0 -> StatTile(
                label = "Projects",
                value = stats.projects.toString(),
                icon = Icons.AutoMirrored.Filled.List,
                modifier = tileModifier,
            )
            1 -> StatTile(
                label = "Builds",
                value = if (stats.realBuilds == 0) "-" else stats.realBuilds.toString(),
                icon = Icons.Filled.Build,
                container = scheme.secondaryContainer,
                tint = scheme.onSecondaryContainer,
                modifier = tileModifier,
            )
            2 -> StatTile(
                label = "Success rate",
                value = stats.successPercent?.let { "$it%" } ?: "-",
                icon = Icons.Filled.CheckCircle,
                container = extras.successContainer,
                tint = extras.onSuccessContainer,
                modifier = tileModifier,
            )
            else -> StatTile(
                label = "Free storage",
                value = if (storage.totalBytes <= 0L) "-" else Formatters.formatBytes(storage.freeBytes),
                icon = Icons.Filled.Info,
                container = scheme.tertiaryContainer,
                tint = scheme.onTertiaryContainer,
                modifier = tileModifier,
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    content: @Composable () -> Unit,
) {
    AlmusCard(modifier = Modifier.fillMaxWidth()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun GitHubCard(connection: GitHubConnection, onConnect: () -> Unit) {
    InfoCard(title = "GitHub connection") {
        val connected = connection is GitHubConnection.Connected
        val status = when (connection) {
            GitHubConnection.NotConnected -> "Not connected"
            is GitHubConnection.Connected -> "Connected as ${connection.username}"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(
                imageVector = if (connected) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (connected) MaterialTheme.almus.success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AlmusButton(
            text = if (connected) "Manage GitHub" else "Connect GitHub",
            onClick = onConnect,
            style = AlmusButtonStyle.Outlined,
        )
    }
}

@Composable
private fun CloudBuildCard(serviceName: String, available: Boolean, onStart: () -> Unit) {
    InfoCard(title = "Cloud build") {
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
        AlmusButton(text = "Cloud build", onClick = onStart, icon = Icons.Filled.Build)
    }
}

@Composable
private fun StorageCard(data: HomeData) {
    InfoCard(title = "Storage and projects") {
        val storage = data.storage
        val reduced = rememberReducedMotion()
        if (storage.totalBytes <= 0L) {
            Text(
                text = "Storage information is not available on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val almostFull = storage.usedFraction > 0.9f
            val animated by animateFloatAsState(
                targetValue = storage.usedFraction.coerceIn(0f, 1f),
                animationSpec = tween(durationMillis = Motion.duration(Motion.Slow, reduced)),
                label = "storageProgress",
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                color = if (almostFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
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
            if (almostFull) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = "Storage is almost full. Builds and backups may fail.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Text(
            text = "Projects: ${data.projectCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
