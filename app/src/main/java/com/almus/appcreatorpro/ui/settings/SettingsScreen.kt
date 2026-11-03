package com.almus.appcreatorpro.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.BuildConfig
import com.almus.appcreatorpro.domain.model.ThemeMode
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.PrototypeBadge
import com.almus.appcreatorpro.ui.components.openAppNotificationSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAbout: () -> Unit,
    onOpenGitHub: () -> Unit,
    bottomBar: @Composable () -> Unit,
    onOpenSigning: () -> Unit = {},
    onOpenArtifacts: () -> Unit = {},
    onOpenDashboard: () -> Unit = {},
    onOpenAiSettings: () -> Unit = {},
    onGenerateWithAi: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val notifications by viewModel.notifications.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    // Coming back from the Android settings may have changed what notifications are allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshNotificationState() }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        bottomBar = bottomBar,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard(title = "Appearance") {
                ThemeOption("Follow system", ThemeMode.SYSTEM, themeMode, viewModel::setThemeMode)
                ThemeOption("Light", ThemeMode.LIGHT, themeMode, viewModel::setThemeMode)
                ThemeOption("Dark", ThemeMode.DARK, themeMode, viewModel::setThemeMode)
            }

            SettingsCard(title = "GitHub") {
                Text(
                    text = "Sign in with GitHub to build your projects in the cloud. The app never contains a token or secret; " +
                        "your sign-in is stored encrypted on this phone and can be removed at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenGitHub, modifier = Modifier.fillMaxWidth()) { Text("GitHub connection") }
            }

            SettingsCard(title = "AI assistant (Gemini)") {
                Text(
                    text = "Chat, generate apps, edit code, review and debug with Google Gemini. The app never contains a Gemini key: " +
                        "it talks to your own ALMUS backend, which holds the key. Code is only sent after you confirm the list of files.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenAiSettings, modifier = Modifier.fillMaxWidth()) { Text("AI backend settings") }
                OutlinedButton(onClick = onGenerateWithAi, modifier = Modifier.fillMaxWidth()) { Text("Generate an app with AI") }
            }

            SettingsCard(title = "Builds") {
                Text(
                    text = "Create or import the key that signs your release builds, and see the APK and AAB files saved on this phone. " +
                        "Keys are encrypted on this phone and are never written to your repository.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onOpenSigning, modifier = Modifier.fillMaxWidth()) { Text("Release signing") }
                OutlinedButton(onClick = onOpenArtifacts, modifier = Modifier.fillMaxWidth()) { Text("Saved APK and AAB files") }
            }

            SettingsCard(title = "Build monitoring") {
                Text(
                    text = "Builds run on GitHub. While one is running, this app checks its status in the background (when your phone " +
                        "allows it and has a connection) and can notify you when it starts, finishes, fails or is cancelled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                notifications?.let { ui ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(text = "Build notifications", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(
                            checked = ui.enabled,
                            onCheckedChange = { wanted ->
                                val needsDialog = viewModel.setNotificationsEnabled(wanted)
                                if (needsDialog && Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                        )
                    }
                    when {
                        ui.working -> Text("On. You will be told when a build starts, finishes or fails.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ui.needsPermission -> Text("Android needs your permission first. Turn the switch off and on again to be asked.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ui.blockedBySystem -> {
                            Text("Notifications are blocked for this app in Android settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { openAppNotificationSettings(context) }, modifier = Modifier.fillMaxWidth()) { Text("Open Android settings") }
                        }
                        else -> Text("Off. Builds are still tracked; you just will not be notified.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = onOpenDashboard, modifier = Modifier.fillMaxWidth()) { Text("Build dashboard") }
            }

            SettingsCard(title = "General") {
                OutlinedButton(
                    onClick = viewModel::showWelcomeAgainNextLaunch,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Show welcome screen again") }
                OutlinedButton(
                    onClick = onOpenAbout,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("About Almus App Creator Pro") }
                HorizontalDivider()
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    badge: String? = null,
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
private fun ThemeOption(
    label: String,
    mode: ThemeMode,
    selectedMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = mode == selectedMode,
                onClick = { onSelect(mode) },
                role = Role.RadioButton,
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = mode == selectedMode, onClick = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
