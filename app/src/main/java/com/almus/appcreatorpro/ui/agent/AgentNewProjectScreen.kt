package com.almus.appcreatorpro.ui.agent

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.agent.LauncherFlow
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.ui.AppViewModelProvider

/** Describe an app -> the agent plans it -> you approve the plan -> only then the project is created and the agent starts in it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentNewProjectScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAgent: (String) -> Unit,
    viewModel: AgentNewProjectViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val launcher = viewModel.launcher
    val flow by launcher.flow.collectAsStateWithLifecycle()
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    var description by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var pkg by rememberSaveable { mutableStateOf("") }

    val created = flow as? LauncherFlow.Created
    LaunchedEffect(created?.projectId) {
        created?.let { onOpenAgent(it.projectId) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Build an app with the agent") },
                navigationIcon = { IconButton(onClick = { launcher.cancel(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (availability as? AiAvailability.NotReady)?.let {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(it.reason)
                        OutlinedButton(onClick = onOpenSettings) { Text("Set up AI") }
                    }
                }
            }
            when (val f = flow) {
                LauncherFlow.Idle, is LauncherFlow.Failed -> {
                    if (f is LauncherFlow.Failed) Text(f.message, color = MaterialTheme.colorScheme.error)
                    Text("Describe the app. The agent writes a plan; no project is created until you approve it.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(description, { description = it }, label = { Text("What should the app do?") }, minLines = 5, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { launcher.plan(description) }, enabled = description.isNotBlank() && availability !is AiAvailability.NotReady, modifier = Modifier.fillMaxWidth()) { Text("Plan the app") }
                }
                is LauncherFlow.Planning -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("Planning... ${f.chars} characters received") }
                    OutlinedButton(onClick = { launcher.cancel() }) { Text("Cancel") }
                }
                is LauncherFlow.PlanReady -> {
                    val p = f.plan
                    LaunchedEffect(p.projectName) { if (name.isBlank()) name = p.projectName.orEmpty(); if (pkg.isBlank()) pkg = p.packageName.orEmpty() }
                    Text(p.summary, style = MaterialTheme.typography.titleSmall)
                    if (p.architecture.isNotBlank()) Text(p.architecture, style = MaterialTheme.typography.bodySmall)
                    p.assumptions.forEach { Text("Assumption: $it", style = MaterialTheme.typography.bodySmall) }
                    p.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("Tasks", style = MaterialTheme.typography.titleSmall)
                    p.tasks.forEach { t ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${t.id} - ${t.title}", style = MaterialTheme.typography.bodyMedium)
                                    Text(t.kind.label + if (t.detail.isNotBlank()) ": ${t.detail.take(140)}" else "", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { launcher.removeTask(t.id) }) { Text("Remove") }
                            }
                        }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pkg, { pkg = it }, label = { Text("Package name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { launcher.approveAndCreate(name, pkg) }, enabled = p.tasks.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Approve plan, create the project and start") }
                    OutlinedButton(onClick = { launcher.reset() }) { Text("Start over") }
                }
                LauncherFlow.Creating -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text("Creating the project...") }
                is LauncherFlow.Created -> Text("Created ${f.projectName}. Opening the agent workspace...")
            }
        }
    }
}
