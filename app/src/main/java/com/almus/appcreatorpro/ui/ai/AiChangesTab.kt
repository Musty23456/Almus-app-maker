package com.almus.appcreatorpro.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.appcreatorpro.ai.AiProjectSession
import com.almus.appcreatorpro.ai.AppliedRecord
import com.almus.appcreatorpro.ai.ChangeEntry
import com.almus.appcreatorpro.ai.ChangeKind
import com.almus.appcreatorpro.ai.ChangeStatus
import com.almus.appcreatorpro.ai.LineDiff
import com.almus.appcreatorpro.ai.ProposedChange
import com.almus.appcreatorpro.debug.DiffKind
import com.almus.appcreatorpro.ui.components.ConfirmDialog
import java.text.DateFormat
import java.util.Date

/** Proposed changes (preview, apply, reject) and the history of applied changes (undo, restore). */
@Composable
fun ChangesTab(session: AiProjectSession, vm: AiAssistantViewModel, onOpenFile: (String) -> Unit) {
    val entries by session.changes.entries.collectAsStateWithLifecycle()
    val history by session.changes.history.collectAsStateWithLifecycle()
    var forceFor by remember { mutableStateOf<AppliedRecord?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isEmpty() && history.isEmpty()) {
            item { Text("No AI changes yet. Proposed changes from Edit and from chat answers appear here. Nothing is written to your files until you apply it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(entries.filter { it.status != ChangeStatus.APPLIED }, key = { it.changeSet.id }) { e ->
            ChangeSetCard(e, session, onApply = { sel -> vm.apply(e.changeSet.id, sel) })
        }
        if (history.isNotEmpty()) {
            item { Text("History", style = MaterialTheme.typography.titleMedium) }
            items(history, key = { it.id }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.appliedAtMillis))} - ${r.files.size} file(s)" + if (r.undone) " - undone" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        r.files.forEach { f -> TextButton(onClick = { onOpenFile(f.path) }) { Text(f.path, style = MaterialTheme.typography.bodySmall) } }
                        if (!r.undone) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.undo(r.id, false) }) { Text("Undo") }
                                TextButton(onClick = { forceFor = r }) { Text("Restore anyway...") }
                            }
                        }
                    }
                }
            }
        }
    }
    forceFor?.let { r ->
        ConfirmDialog(
            title = "Restore anyway?",
            message = "This puts the files back exactly as they were before \"${r.title}\", and discards any edits you made to them since.",
            confirmLabel = "Restore", destructive = true,
            onConfirm = { vm.undo(r.id, true); forceFor = null }, onDismiss = { forceFor = null },
        )
    }
}

@Composable
private fun ChangeSetCard(entry: ChangeEntry, session: AiProjectSession, onApply: (Set<String>) -> Unit) {
    val set = entry.changeSet
    val selected = remember(set.id) { mutableStateListOf<String>().also { it.addAll(set.paths) } }
    val rejected = entry.status == ChangeStatus.REJECTED
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(set.title + if (rejected) " (rejected)" else "", style = MaterialTheme.typography.titleMedium)
            if (set.summary.isNotBlank()) Text(set.summary, style = MaterialTheme.typography.bodyMedium)
            set.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text("Affected files: ${set.changes.size}", style = MaterialTheme.typography.labelMedium)
            set.changes.forEach { c ->
                ChangeFileRow(c, checked = c.path in selected, enabled = !rejected, onToggle = { on -> if (on) selected.add(c.path) else selected.remove(c.path) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (rejected) {
                    OutlinedButton(onClick = { session.changes.bringBack(set.id) }) { Text("Bring back") }
                    TextButton(onClick = { session.changes.discard(set.id) }) { Text("Remove") }
                } else {
                    Button(onClick = { onApply(selected.toSet()) }, enabled = selected.isNotEmpty()) { Text("Apply changes") }
                    OutlinedButton(onClick = { session.changes.reject(set.id) }) { Text("Reject") }
                }
            }
        }
    }
}

@Composable
private fun ChangeFileRow(c: ProposedChange, checked: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onToggle, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(c.path, style = MaterialTheme.typography.bodyMedium)
                Text(
                    (if (c.kind == ChangeKind.CREATE) "New file" else "Changed") + " +${c.added} -${c.removed}" + if (c.explanation.isNotBlank()) " - ${c.explanation}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (open) "Hide" else "Before/after", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (open) DiffView(c)
    }
}

/** Before/after as a line diff: removed lines red, added lines green, a few unchanged lines around each change. */
@Composable
private fun DiffView(c: ProposedChange) {
    val rows = remember(c) { LineDiff.collapse(c.diff, 3).take(600) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(6.dp)) {
        rows.forEach { l ->
            if (l == null) {
                Text("...", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val (prefix, bg) = when (l.kind) {
                    DiffKind.ADDED -> "+ " to Color(0x3300C853)
                    DiffKind.REMOVED -> "- " to Color(0x33FF1744)
                    DiffKind.CONTEXT -> "  " to Color.Transparent
                }
                Text(prefix + l.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.background(bg))
            }
        }
    }
}
