package com.almus.appcreatorpro.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.appcreatorpro.ai.AiProjectSession
import com.almus.appcreatorpro.ai.ContextBundle
import com.almus.appcreatorpro.ai.EditFlow
import com.almus.appcreatorpro.ai.ReviewCategory
import com.almus.appcreatorpro.ai.ReviewFlow
import com.almus.appcreatorpro.ai.ReviewSeverity

@Composable
private fun ProgressBlock(text: String, chars: Int, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(2.dp))
                Text(text, style = MaterialTheme.typography.titleSmall)
            }
            if (chars > 0) Text("Received ${chars / 1000} K characters", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun FailureBlock(message: String, retryable: Boolean, onBack: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message)
            Button(onClick = onBack) { Text(if (retryable) "Try again" else "Back") }
        }
    }
}

/** What will be sent: file names, sizes, removed secrets and skipped files. The user confirms or unticks files. */
@Composable
private fun SendPreview(bundle: ContextBundle, reasons: Map<String, String>, selected: MutableList<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("These files will be sent to your AI backend:", style = MaterialTheme.typography.titleSmall)
        bundle.files.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { if (f.path in selected) selected.remove(f.path) else selected.add(f.path) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = f.path in selected, onCheckedChange = { on -> if (on) selected.add(f.path) else selected.remove(f.path) })
                Column {
                    Text(f.path, style = MaterialTheme.typography.bodyMedium)
                    val extra = buildList {
                        add("${f.content.length} characters")
                        if (f.truncated) add("shortened")
                        if (f.redactions > 0) add("${f.redactions} secret(s) removed")
                        reasons[f.path]?.let { add(it) }
                    }
                    Text(extra.joinToString(" - "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (bundle.skipped.isNotEmpty()) {
            Text("Not sent:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            bundle.skipped.forEach { Text("${it.path}: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (bundle.dependencies.isNotEmpty()) Text("Also sent: the project's dependency names and the list of file paths (no other file content).", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EditTab(session: AiProjectSession, paths: List<String>, onShowChanges: () -> Unit) {
    val flow by session.edit.collectAsStateWithLifecycle()
    var instruction by rememberSaveable { mutableStateOf("") }
    var auto by rememberSaveable { mutableStateOf(true) }
    var manual by remember { mutableStateOf(listOf<String>()) }
    var picking by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            when (val f = flow) {
                EditFlow.Idle -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Describe a change to your project. The AI looks at the files it needs, proposes the changes, and you review every file before anything is written.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = instruction, onValueChange = { instruction = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                        label = { Text("What should change?") }, placeholder = { Text("Change the login screen design to a modern blue gradient") },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(checked = auto, onCheckedChange = { auto = it })
                        Text("Let the AI choose the files (it first sees only the file list)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { picking = true }) { Text(if (manual.isEmpty()) "Pick files myself" else "My files (${manual.size})") }
                    Button(onClick = { session.startEdit(instruction, manual, auto) }, enabled = instruction.trim().length >= 5) { Text("Find the files") }
                }
                is EditFlow.Working -> ProgressBlock(f.stage, f.chars) { session.cancelEdit() }
                is EditFlow.ConfirmFiles -> {
                    val selected = remember(f.bundle) { mutableStateListOf<String>().also { l -> l.addAll(f.bundle.files.map { it.path }) } }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (f.summary.isNotBlank()) Text(f.summary, style = MaterialTheme.typography.bodyMedium)
                        SendPreview(f.bundle, f.reasons, selected)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { session.confirmEdit(selected.toList()) }, enabled = selected.isNotEmpty()) { Text("Send ${selected.size} file(s)") }
                            OutlinedButton(onClick = { session.cancelEdit() }) { Text("Cancel") }
                        }
                    }
                }
                is EditFlow.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.summary.ifBlank { "The AI finished." }, style = MaterialTheme.typography.bodyMedium)
                    f.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (f.rejected.isNotEmpty()) {
                        Text("Parts that were not used:", style = MaterialTheme.typography.titleSmall)
                        f.rejected.forEach { Text("${it.path}: ${it.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (f.changeSetId != null) Button(onClick = { session.resetEdit(); onShowChanges() }) { Text("Review changes") }
                        OutlinedButton(onClick = { session.resetEdit() }) { Text("New request") }
                    }
                }
                is EditFlow.Failed -> FailureBlock(f.message, f.retryable) { session.resetEdit() }
            }
        }
    }
    if (picking) FilePickerDialog(paths, manual, onDismiss = { picking = false }, onDone = { manual = it; picking = false })
}

@Composable
fun ReviewTab(session: AiProjectSession, paths: List<String>, onOpenFile: (String) -> Unit) {
    val flow by session.review.collectAsStateWithLifecycle()
    var focus by remember { mutableStateOf<ReviewCategory?>(null) }
    var chosen by remember { mutableStateOf(listOf<String>()) }
    var picking by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val f = flow) {
            ReviewFlow.Idle -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Get a code review: quality, security, performance, architecture and best practices. Choose the files to review.", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = focus == null, onClick = { focus = null }, label = { Text("All") })
                        FilterChip(selected = focus == ReviewCategory.SECURITY, onClick = { focus = ReviewCategory.SECURITY }, label = { Text("Security") })
                        FilterChip(selected = focus == ReviewCategory.PERFORMANCE, onClick = { focus = ReviewCategory.PERFORMANCE }, label = { Text("Performance") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = focus == ReviewCategory.ARCHITECTURE, onClick = { focus = ReviewCategory.ARCHITECTURE }, label = { Text("Architecture") })
                        FilterChip(selected = focus == ReviewCategory.BEST_PRACTICE, onClick = { focus = ReviewCategory.BEST_PRACTICE }, label = { Text("Best practices") })
                    }
                    OutlinedButton(onClick = { picking = true }) { Text(if (chosen.isEmpty()) "Choose files" else "Files (${chosen.size})") }
                    Button(onClick = { session.prepareReview(chosen, focus) }, enabled = chosen.isNotEmpty()) { Text("Review") }
                }
            }
            is ReviewFlow.Confirm -> item {
                val selected = remember(f.bundle) { mutableStateListOf<String>().also { l -> l.addAll(f.bundle.files.map { it.path }) } }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SendPreview(f.bundle, emptyMap(), selected)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (selected.size == f.bundle.files.size) session.confirmReview() else { session.cancelReview(); session.prepareReview(selected.toList(), f.focus) }
                        }, enabled = selected.isNotEmpty()) { Text(if (selected.size == f.bundle.files.size) "Send ${selected.size} file(s)" else "Update list") }
                        OutlinedButton(onClick = { session.cancelReview() }) { Text("Cancel") }
                    }
                }
            }
            is ReviewFlow.Working -> item { ProgressBlock("Reviewing...", f.chars) { session.cancelReview() } }
            is ReviewFlow.Failed -> item { FailureBlock(f.message, f.retryable) { session.resetReview() } }
            is ReviewFlow.Done -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(f.result.summary, style = MaterialTheme.typography.bodyMedium)
                        Text("${f.result.findings.size} finding(s) in ${f.reviewedPaths.size} file(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { session.resetReview() }) { Text("New review") }
                    }
                }
                items(f.result.findings) { r ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (r.severity == ReviewSeverity.HIGH) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${r.severity.label} - ${r.category.label}", style = MaterialTheme.typography.labelMedium)
                            Text(r.title, style = MaterialTheme.typography.titleSmall)
                            if (r.path != null) TextButton(onClick = { onOpenFile(r.path) }) { Text(r.path + (r.line?.let { l -> ":$l" } ?: "")) }
                            if (r.detail.isNotBlank()) Text(r.detail, style = MaterialTheme.typography.bodyMedium)
                            if (r.suggestion.isNotBlank()) Text("Suggestion: ${r.suggestion}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    if (picking) FilePickerDialog(paths, chosen, onDismiss = { picking = false }, onDone = { chosen = it; picking = false })
}
