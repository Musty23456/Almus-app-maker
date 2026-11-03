package com.almus.appcreatorpro.ui.dashboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.service.monitor.NotificationUi
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.components.BuildStatusPill
import com.almus.appcreatorpro.ui.components.EmptyView
import com.almus.appcreatorpro.ui.components.ErrorView
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.openAppNotificationSettings
import com.almus.appcreatorpro.util.Formatters
import androidx.compose.ui.platform.LocalContext

/**
 * Phase 7: every build at a glance - active builds with live status, finished builds with duration and artifact
 * availability - plus refresh, cancel and the notification state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildDashboardScreen(
    onBack: () -> Unit,
    onOpenBuild: (String) -> Unit,
    onOpenFullHistory: () -> Unit,
    viewModel: BuildDashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val cancelling by viewModel.cancelling.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    // Coming back from the Android settings may have changed what notifications are allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotificationState() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Build dashboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = onOpenFullHistory) { Text("Full history") }
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = viewModel::refresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh build status") }
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val current = state) {
                UiState.Loading -> LoadingView(message = "Loading builds...")
                is UiState.Error -> ErrorView(message = current.message, onRetry = viewModel::refresh)
                is UiState.Success -> DashboardContent(
                    data = current.data,
                    filter = filter,
                    cancelling = cancelling,
                    notifications = notifications,
                    onFilter = viewModel::setFilter,
                    onOpenBuild = onOpenBuild,
                    onCancel = viewModel::cancel,
                    onAllowNotifications = {
                        val needsDialog = viewModel.enableNotifications()
                        if (needsDialog && Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onOpenNotificationSettings = { openAppNotificationSettings(context) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    data: DashboardState,
    filter: DashboardFilter,
    cancelling: Set<String>,
    notifications: NotificationUi?,
    onFilter: (DashboardFilter) -> Unit,
    onOpenBuild: (String) -> Unit,
    onCancel: (String) -> Unit,
    onAllowNotifications: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    val now = System.currentTimeMillis()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummaryRow(data.summary) }

        if (notifications != null && (notifications.needsPermission || notifications.blockedBySystem)) {
            item { NotificationBanner(notifications, onAllowNotifications, onOpenNotificationSettings) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DashboardFilter.entries.forEach { option ->
                    FilterChip(selected = option == filter, onClick = { onFilter(option) }, label = { Text(option.label) })
                }
            }
        }

        if (data.isEmpty) {
            item {
                EmptyView(
                    icon = Icons.Filled.Notifications,
                    title = if (data.summary.total == 0) "No builds yet" else "Nothing here",
                    message = if (data.summary.total == 0) {
                        "Start a cloud build from a project. Its progress shows here, also after you close the app."
                    } else {
                        "No builds match this filter."
                    },
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                )
            }
        }

        if (data.active.isNotEmpty()) {
            item { Text("Active builds", style = MaterialTheme.typography.titleMedium) }
            items(data.active, key = { "active-" + it.id }) { item ->
                ActiveBuildCard(
                    item = item,
                    now = now,
                    cancelling = item.id in cancelling,
                    onOpen = { onOpenBuild(item.id) },
                    onCancel = { onCancel(item.id) },
                )
            }
        }

        if (data.history.isNotEmpty()) {
            item { Text("Build history", style = MaterialTheme.typography.titleMedium) }
            items(data.history, key = { "history-" + it.id }) { item ->
                HistoryBuildCard(item = item, now = now, onOpen = { onOpenBuild(item.id) })
            }
        }
    }
}

@Composable
private fun SummaryRow(summary: DashboardSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatCard("Active", summary.active, Modifier.weight(1f))
        StatCard("Done", summary.completed, Modifier.weight(1f))
        StatCard("Failed", summary.failed, Modifier.weight(1f))
        StatCard("Cancelled", summary.cancelled, Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(label: String, count: Int, modifier: Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun NotificationBanner(ui: NotificationUi, onAllow: () -> Unit, onSettings: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Get build alerts", style = MaterialTheme.typography.titleSmall)
            if (ui.needsPermission) {
                Text("Allow notifications to be told when a build starts, finishes or fails - even when the app is closed.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onAllow) { Text("Allow notifications") }
            } else {
                Text("Notifications are turned off for this app in Android settings, so build alerts are not shown.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onSettings) { Text("Open Android settings") }
            }
        }
    }
}

@Composable
private fun ActiveBuildCard(item: DashboardItem, now: Long, cancelling: Boolean, onOpen: () -> Unit, onCancel: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.record.projectName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.buildType} - ${item.statusText}", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                BuildStatusPill(status = item.record.status)
            }
            if (item.needsAttention) {
                Text(item.record.detail ?: "Checking stopped. Tap refresh to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                item.record.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
            }
            Text(
                text = listOfNotNull(
                    item.durationSeconds?.let { "Running for ${Formatters.formatDuration(it)}" },
                    "Started ${Formatters.relativeTime(now, item.record.startedAtMillis)}",
                    if (item.isLive) "Live" else "Checked in the background",
                ).joinToString(" | "),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, enabled = item.canCancel && !cancelling) {
                    Text(if (cancelling) "Cancelling..." else "Cancel build")
                }
                TextButton(onClick = onOpen) { Text("Details") }
            }
        }
    }
}

@Composable
private fun HistoryBuildCard(item: DashboardItem, now: Long, onOpen: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.record.projectName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.buildType, style = MaterialTheme.typography.bodySmall, color = muted)
                }
                BuildStatusPill(status = item.record.status)
            }
            Text(
                text = listOfNotNull(
                    item.durationSeconds?.let { "Took ${Formatters.formatDuration(it)}" },
                    Formatters.relativeTime(now, item.record.startedAtMillis),
                ).joinToString(" | "),
                style = MaterialTheme.typography.labelSmall,
                color = muted,
            )
            Text(
                text = item.artifact.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.artifact.availability == ArtifactAvailability.AVAILABLE || item.artifact.availability == ArtifactAvailability.ON_PHONE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    muted
                },
            )
            val reason = item.record.detail
            if (!reason.isNullOrBlank() && item.group != BuildGroup.COMPLETED) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
