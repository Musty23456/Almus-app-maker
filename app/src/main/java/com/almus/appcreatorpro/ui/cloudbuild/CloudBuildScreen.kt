package com.almus.appcreatorpro.ui.cloudbuild

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.service.build.PreflightResult
import com.almus.appcreatorpro.service.build.WorkflowCheck
import com.almus.appcreatorpro.ui.AppViewModelProvider
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.LoadingView
import com.almus.appcreatorpro.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudBuildScreen(
    projectId: String,
    onBack: () -> Unit,
    onOpenGitHub: () -> Unit,
    onChooseRepository: (String) -> Unit,
    onOpenBuild: (String) -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: CloudBuildViewModel = viewModel(
        key = "cloudbuild-$projectId",
        factory = AppViewModelProvider.cloudBuildFactory(projectId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startedId by viewModel.startedBuildId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    // Returning from the GitHub or repository screens: pick up the new sign-in / repository.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    LaunchedEffect(startedId) {
        startedId?.let {
            viewModel.consumeStarted()
            onOpenBuild(it)
        }
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cloud build") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = onOpenHistory) { Text("History") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) {
                LoadingView(message = "Loading...")
            } else if (state.project == null) {
                BodyText(state.error ?: "Project not found.", modifier = Modifier.padding(16.dp))
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SectionCard(state.project!!.name) {
                        BodyText(
                            "${state.project!!.type.displayName}. The project's files are committed to a folder in your GitHub " +
                                "repository and built there by GitHub Actions. A debug APK is produced for testing.",
                        )
                    }

                    SectionCard("1. GitHub", tone = if (state.connected) CardTone.Normal else CardTone.Error) {
                        BodyText(if (state.connected) "Connected and verified." else "Not connected. Builds cannot start until GitHub confirms your sign-in.")
                        OutlinedButton(onClick = onOpenGitHub) { Text(if (state.connected) "GitHub settings" else "Connect GitHub") }
                    }

                    SectionCard("2. Repository and branch") {
                        val config = state.config
                        if (config == null) {
                            BodyText("No repository chosen for this project yet.")
                        } else {
                            Text("${config.repoFullName}  (${config.branch})", style = MaterialTheme.typography.bodyLarge)
                        }
                        OutlinedButton(onClick = { onChooseRepository(projectId) }, enabled = state.connected) {
                            Text(if (config == null) "Choose repository" else "Change repository or branch")
                        }
                    }

                    SectionCard("3. Build settings") {
                        OutlinedTextField(
                            value = state.dir,
                            onValueChange = viewModel::onDirChange,
                            label = { Text("Folder in the repository") },
                            singleLine = true,
                            isError = state.dirError != null,
                            supportingText = { Text(state.dirError ?: "Only this folder is replaced by an upload.") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.gradle,
                            onValueChange = viewModel::onGradleChange,
                            label = { Text("Gradle version") },
                            singleLine = true,
                            isError = state.gradleError != null,
                            supportingText = { Text(state.gradleError ?: "Must match your Android Gradle Plugin (AGP 8.7 needs 8.9+).") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    SectionCard("4. Workflow setup (once per repository)") {
                        BodyText(
                            "GitHub only runs builds from a workflow file in the repository. Add ${CloudBuildConfig.WORKFLOW_PATH} " +
                                "on the default branch (and on your build branch if different). The app does not write workflow files " +
                                "itself, so a stolen token could never change what runs.",
                        )
                        BodyText("On your phone: tap Copy, tap Open GitHub, paste the text, then Commit changes.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(viewModel.workflowText()))
                            }) { Text("Copy workflow") }
                            val config = state.config
                            OutlinedButton(
                                enabled = config != null,
                                onClick = {
                                    if (config != null) {
                                        uriHandler.openUri(
                                            "https://github.com/${config.repoFullName}/new/${config.branch}?filename=${CloudBuildConfig.WORKFLOW_PATH}",
                                        )
                                    }
                                },
                            ) { Text("Open GitHub") }
                        }
                    }

                    SectionCard("5. Check and build") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = viewModel::check, enabled = state.connected && state.config != null && !state.checking && !state.starting) {
                                Text(if (state.checking) "Checking..." else "Check")
                            }
                            Button(onClick = viewModel::startBuild, enabled = state.canStart) {
                                Text(if (state.starting) "Uploading..." else "Upload and build")
                            }
                        }
                        if (state.checking || state.starting) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        BodyText("Check is read-only. Upload and build commits the project to the folder above and starts GitHub Actions.")
                    }

                    if (state.rejected.isNotEmpty()) {
                        SectionCard("Nothing was uploaded", tone = CardTone.Error) {
                            state.rejected.forEach { BodyText("- $it") }
                        }
                    }
                    state.preflight?.let { PreflightCard(it) }
                }
            }
        }
    }
}

@Composable
private fun PreflightCard(result: PreflightResult) {
    SectionCard(if (result.canBuild) "Ready to build" else "Not ready", tone = if (result.canBuild) CardTone.Success else CardTone.Error) {
        val v = result.validation
        BodyText("Project files: ${v.fileCount} (${v.totalBytes / 1024} KB)")
        result.repo?.let { BodyText("Repository: ${it.fullName}${if (it.isPrivate) " (private)" else " (public)"}") }
        result.branch?.let { BodyText("Branch: ${it.name}${if (it.isProtected) " (protected: uploads may be refused)" else ""}") }
        result.latestCommit?.let { BodyText("Latest commit: ${it.shortSha} ${it.title}") }
        result.workflow?.let {
            BodyText(
                "Workflow: " + when (it) {
                    WorkflowCheck.OK -> "installed"
                    WorkflowCheck.MISSING -> "missing"
                    WorkflowCheck.OUTDATED -> "outdated"
                    WorkflowCheck.NOT_ON_DEFAULT_BRANCH -> "missing on the default branch"
                },
            )
        }
        result.blockers.forEach { Text("- $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        v.warnings.forEach { BodyText("Warning: $it") }
    }
}
