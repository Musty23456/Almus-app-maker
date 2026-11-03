package com.almus.appcreatorpro.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.almus.appcreatorpro.ai.GeneratorFlow
import com.almus.appcreatorpro.ai.ProjectPlan
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.editor.Language
import com.almus.appcreatorpro.ui.AppViewModelProvider

/** Describe an app -> review the plan -> review the generated files -> create the project. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiGeneratorScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProject: (String) -> Unit,
    viewModel: AiGeneratorViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val flow by viewModel.generator.flow.collectAsStateWithLifecycle()
    val availability by viewModel.availability.collectAsStateWithLifecycle()
    var description by rememberSaveable { mutableStateOf("") }
    val generator = viewModel.generator

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Generate an app with AI") },
                navigationIcon = { IconButton(onClick = { generator.cancel(); generator.reset(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
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
                GeneratorFlow.Idle -> {
                    Text("Describe the app you want. The AI first writes a plan for you to review, then generates the project files. Nothing is created until you confirm.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = description, onValueChange = { description = it }, modifier = Modifier.fillMaxWidth(), minLines = 5,
                        label = { Text("What should the app do?") },
                        placeholder = { Text("Create a modern offline notes application using Kotlin with dark mode, search, and local database storage.") },
                    )
                    Button(onClick = { generator.plan(description) }, enabled = description.trim().length >= 15 && availability is AiAvailability.Ready) { Text("Create a plan") }
                    TextButton(onClick = { generator.generate(description, null) }, enabled = description.trim().length >= 15 && availability is AiAvailability.Ready) { Text("Skip the plan and generate directly") }
                }
                is GeneratorFlow.Planning -> Busy("Planning your app...", f.chars) { generator.cancel() }
                is GeneratorFlow.PlanReady -> PlanView(f.description, f.plan, onChange = viewModel::updatePlan, onApprove = { generator.generate(f.description, f.plan) }, onDiscard = { generator.reset() })
                is GeneratorFlow.Generating -> Busy("Generating project files... this can take a minute", f.chars) { generator.cancel() }
                is GeneratorFlow.Review -> ReviewView(f, onCreate = { name, excluded -> generator.create(name, excluded) }, onDiscard = { generator.reset() })
                GeneratorFlow.Creating -> Busy("Creating the project...", 0) { }
                is GeneratorFlow.Created -> {
                    Text("Project \"${f.projectName}\" created with ${f.filesWritten} file(s).", style = MaterialTheme.typography.titleMedium)
                    f.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("Next: open it, run \"Check code & errors\" in the editor menu, then build it in the cloud. You can undo the AI's files from the project's AI assistant (Changes > History).", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { onOpenProject(f.projectId) }) { Text("Open project") }
                }
                is GeneratorFlow.Failed -> {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(f.message)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (f.projectId != null) Button(onClick = { onOpenProject(f.projectId) }) { Text("Open the project") }
                                OutlinedButton(onClick = { generator.reset() }) { Text(if (f.retryable) "Try again" else "Back") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String, chars: Int, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(text, style = MaterialTheme.typography.titleSmall)
            }
            if (chars > 0) Text("Received ${chars / 1000} K characters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun PlanView(description: String, plan: ProjectPlan, onChange: (ProjectPlan) -> Unit, onApprove: () -> Unit, onDiscard: () -> Unit) {
    var name by rememberSaveable(plan.projectName) { mutableStateOf(plan.projectName) }
    var pkg by rememberSaveable(plan.packageName) { mutableStateOf(plan.packageName) }
    Text("Development plan - review it before anything is generated", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(value = name, onValueChange = { name = it; onChange(plan.copy(projectName = it, packageName = pkg)) }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = pkg, onValueChange = { pkg = it; onChange(plan.copy(projectName = name, packageName = it)) }, label = { Text("Package name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
    if (plan.technologies.isNotEmpty()) {
        Text("Technologies", style = MaterialTheme.typography.titleSmall)
        plan.technologies.forEach { Text("- ${it.name}: ${it.purpose}", style = MaterialTheme.typography.bodyMedium) }
    }
    if (plan.dependencies.isNotEmpty()) {
        Text("Dependencies", style = MaterialTheme.typography.titleSmall)
        plan.dependencies.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (plan.architecture.isNotBlank()) { Text("Architecture", style = MaterialTheme.typography.titleSmall); Text(plan.architecture, style = MaterialTheme.typography.bodyMedium) }
    if (plan.structure.isNotEmpty()) {
        Text("Planned files", style = MaterialTheme.typography.titleSmall)
        plan.structure.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (plan.steps.isNotEmpty()) {
        Text("Steps", style = MaterialTheme.typography.titleSmall)
        plan.steps.forEachIndexed { i, s -> Text("${i + 1}. ${s.title}: ${s.detail}", style = MaterialTheme.typography.bodyMedium) }
    }
    if (plan.risks.isNotEmpty()) {
        Text("Risks", style = MaterialTheme.typography.titleSmall)
        plan.risks.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onApprove) { Text("Approve and generate") }
        OutlinedButton(onClick = onDiscard) { Text("Discard") }
    }
}

@Composable
private fun ReviewView(f: GeneratorFlow.Review, onCreate: (String, Set<String>) -> Unit, onDiscard: () -> Unit) {
    val outcome = f.outcome
    var name by rememberSaveable(outcome.project.projectName) { mutableStateOf(outcome.project.projectName) }
    val excluded = remember(outcome) { mutableStateListOf<String>() }
    var viewing by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    Text("Generated ${outcome.report.accepted.size} file(s) - review before creating", style = MaterialTheme.typography.titleMedium)
    if (outcome.project.summary.isNotBlank()) Text(outcome.project.summary, style = MaterialTheme.typography.bodyMedium)
    if (!outcome.finishedCleanly) Text("The answer reached the length limit and may be incomplete.", color = MaterialTheme.colorScheme.error)
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Text("Package: ${outcome.project.packageName}", style = MaterialTheme.typography.bodySmall)

    if (outcome.report.rejected.isNotEmpty()) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not used (failed safety checks):", style = MaterialTheme.typography.titleSmall)
                outcome.report.rejected.forEach { Text("${it.path.ifBlank { "(answer)" }}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    if (outcome.report.warnings.isNotEmpty() || outcome.report.codeCheckErrors.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Check these before building:", style = MaterialTheme.typography.titleSmall)
                outcome.report.warnings.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                outcome.report.codeCheckErrors.forEach { Text("- ${it.path}: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    outcome.project.notes.forEach { Text("Note: $it", style = MaterialTheme.typography.bodySmall) }

    outcome.report.accepted.forEach { file ->
        Row(Modifier.fillMaxWidth().clickable { viewing = if (viewing == file.path) null else file.path }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = file.path !in excluded, onCheckedChange = { on -> if (on) excluded.remove(file.path) else excluded.add(file.path) })
            Column(Modifier.weight(1f)) {
                Text(file.path, style = MaterialTheme.typography.bodyMedium)
                Text("${file.content.lines().size} lines", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (viewing == file.path) "Hide" else "View", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (viewing == file.path) CodeBlockView(file.content, Language.fromFileName(file.path), onCopy = { clipboard.setText(AnnotatedString(it)) }, title = file.path)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onCreate(name, excluded.toSet()) }, enabled = outcome.report.accepted.size > excluded.size) { Text("Create project") }
        OutlinedButton(onClick = onDiscard) { Text("Discard") }
    }
}
