package com.almus.appcreatorpro.ui.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.almus.appcreatorpro.debug.AiOutcome
import com.almus.appcreatorpro.debug.AiTask
import com.almus.appcreatorpro.debug.AiUi
import com.almus.appcreatorpro.debug.DebugSession
import com.almus.appcreatorpro.debug.DebugState
import com.almus.appcreatorpro.debug.DiffKind
import com.almus.appcreatorpro.repair.FileDecision
import com.almus.appcreatorpro.repair.PatchProblem
import com.almus.appcreatorpro.repair.ProblemSeverity
import com.almus.appcreatorpro.repair.RepairFilePatch
import com.almus.appcreatorpro.repair.RepairLogs
import com.almus.appcreatorpro.repair.RepairPhase
import com.almus.appcreatorpro.repair.RepairPreview
import com.almus.appcreatorpro.repair.RepairSession
import com.almus.appcreatorpro.repair.RepairState
import com.almus.appcreatorpro.repair.RepairText
import com.almus.appcreatorpro.repair.SideKind
import com.almus.appcreatorpro.repair.ValidationStatus
import com.almus.appcreatorpro.ui.components.BodyText
import com.almus.appcreatorpro.ui.components.CardTone
import com.almus.appcreatorpro.ui.components.SectionCard
import com.almus.appcreatorpro.ui.theme.almus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 23: the Repair tab. Diagnosed error -> suggested repair -> patch -> validation -> your approval -> apply -> rebuild.
 * Nothing is written until the user accepts files and presses Apply, and the tab never says an error is fixed: only a new
 * build can show that.
 */
@Composable
internal fun RepairTab(
    vm: DebugViewModel,
    s: DebugSession,
    state: DebugState,
    snackbar: SnackbarHostState,
    onOpenFile: (String, Int?, Int?) -> Unit,
    onRebuild: () -> Unit,
) {
    val repair by vm.repair.collectAsStateWithLifecycle()
    val r = repair
    if (r == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            SectionCard("Code repair") { BodyText("The repair engine is still loading, or is not available for this project.") }
        }
        return
    }
    RepairContent(vm, r, state, snackbar, onOpenFile, onRebuild)
}

@Composable
private fun RepairContent(
    vm: DebugViewModel,
    r: RepairSession,
    state: DebugState,
    snackbar: SnackbarHostState,
    onOpenFile: (String, Int?, Int?) -> Unit,
    onRebuild: () -> Unit,
) {
    val rs by r.state.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val aiNote by vm.aiNote.collectAsStateWithLifecycle()
    val preview by vm.repairPreview.collectAsStateWithLifecycle()
    val conflict by vm.rollbackConflict.collectAsStateWithLifecycle()
    val plan = rs.plan

    LaunchedEffect(rs.message) {
        rs.message?.let { snackbar.showSnackbar(it); vm.dismissRepairMessage() }
    }

    preview?.let { p ->
        val patch = plan?.patch(p.path)
        RepairPreviewDialog(
            preview = p,
            patch = patch,
            decision = rs.decisionOf(p.path),
            onAccept = { vm.acceptRepair(p.path); vm.closeRepairPreview() },
            onReject = { vm.rejectRepair(p.path) },
            onClose = vm::closeRepairPreview,
        )
    }
    conflict?.let { (applyId, paths) ->
        AlertDialog(
            onDismissRequest = vm::dismissRollbackConflict,
            title = { Text("Files were edited after the repair") },
            text = {
                Text(
                    "These files changed after the repair was applied:\n" + paths.joinToString("\n") +
                        "\n\nRolling back anyway restores the original text and replaces your later edits. The current text is saved first, but it is not shown in the editor.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.rollbackRepair(applyId, force = true) }) { Text("Roll back anyway") } },
            dismissButton = { TextButton(onClick = vm::dismissRollbackConflict) { Text("Keep my edits") } },
        )
    }
    if (ai is AiUi.Confirm) AiConfirmDialog(vm, ai as AiUi.Confirm)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Code repair", badge = rs.phase.label) {
                BodyText(
                    "Build failure, diagnosis, suggested repair, patch, validation, your approval, apply, rebuild. " +
                        "Your files are only read until you accept them and press Apply. The originals are saved first and every change can be rolled back.",
                )
                BodyText(RepairText.NOT_A_COMPILER)
            }
        }

        // ---- choose the error to repair
        val roots = state.diagnosis.rootCauses.take(MAX_LISTED_ERRORS)
        if (roots.isEmpty()) {
            item { BodyText("There is no diagnosed build error to repair. Open a build with errors (Analyze errors) first.") }
        } else {
            item { Text("Diagnosed errors", style = MaterialTheme.typography.titleSmall) }
            items(roots, key = { it.id }) { d ->
                SectionCard(d.category.label, badge = d.severity.label) {
                    Text(d.issue.message.take(300), style = MaterialTheme.typography.bodyMedium)
                    BodyText("Files: " + (d.affectedFiles.filter { it.inProject }.take(3).joinToString { it.label }.ifBlank { "none named in the log" }))
                    OutlinedButton(enabled = !rs.busy, onClick = { vm.prepareRepair(d.id) }) { Text(if (plan?.issueId == d.id) "Prepare again" else "Prepare repair") }
                }
            }
        }

        // ---- the plan
        if (plan != null) {
            item {
                SectionCard("Repair plan") {
                    Text(plan.issueTitle, style = MaterialTheme.typography.bodyMedium)
                    BodyText("Diagnosis: " + plan.diagnosisNote)
                    if (plan.targets.isNotEmpty()) {
                        Text("Affected files", style = MaterialTheme.typography.labelMedium)
                        plan.targets.forEach { t ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    t.path + (t.line?.let { ":$it" } ?: "") + "  (" + t.basis.label + ")" + if (t.exists) "" else " - not in the project",
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.weight(1f),
                                )
                                if (t.exists) TextButton(onClick = { onOpenFile(t.path, t.line, null) }) { Text("Open") }
                            }
                        }
                    }
                    plan.notices.forEach { BodyText(it) }
                    if (plan.skipped.isNotEmpty()) {
                        Text("Not included", style = MaterialTheme.typography.labelMedium)
                        plan.skipped.forEach { BodyText("${it.title} (${it.path}): ${it.reason}") }
                    }
                }
            }
            items(plan.patches, key = { it.path }) { patch -> PatchCard(vm, patch, rs) }

            if (plan.hasPatches) {
                item {
                    SectionCard("Your decision") {
                        BodyText("Accepted: ${rs.acceptedPaths.size} of ${plan.patches.size} file(s). Accepting only marks a file; nothing is written until you press Apply.")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !rs.busy, onClick = vm::acceptAllRepair) { Text("Accept all valid") }
                            TextButton(enabled = !rs.busy, onClick = vm::rejectAllRepair) { Text("Reject all") }
                        }
                        Button(enabled = rs.canApply, onClick = vm::applyRepair) {
                            Text(if (rs.acceptedPaths.isEmpty()) "Apply (accept a file first)" else "Apply ${rs.acceptedPaths.size} accepted file(s)")
                        }
                    }
                }
            }
            if (rs.phase == RepairPhase.APPLIED_UNVERIFIED) {
                item {
                    SectionCard("Applied, not verified", tone = CardTone.Normal) {
                        BodyText(RepairText.NOT_VERIFIED)
                        Button(onClick = onRebuild) { Text("Rebuild") }
                    }
                }
            }
            item { TextButton(onClick = vm::discardRepair) { Text("Close this plan") } }
        }

        // ---- need a patch? ask the AI (confirmed first)
        item { AiPatchCard(vm, ai, aiNote, rs, state) }

        // ---- record and rollback
        item { HistoryCard(vm, rs) }
    }
}

@Composable
private fun PatchCard(vm: DebugViewModel, patch: RepairFilePatch, rs: RepairState) {
    val decision = rs.decisionOf(patch.path)
    SectionCard(
        title = patch.path.substringAfterLast('/'),
        badge = patch.origin.label,
        tone = when {
            decision == FileDecision.APPLIED -> CardTone.Success
            !patch.validation.canApply -> CardTone.Error
            else -> CardTone.Normal
        },
    ) {
        Text(patch.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(patch.title, fontWeight = FontWeight.Bold)
        if (patch.explanation.isNotBlank()) BodyText(patch.explanation)
        BodyText("+${patch.added} / -${patch.removed} lines")
        patch.locationNote?.let { BodyText(it) }
        Text(patch.validation.status.label, color = statusColor(patch.validation.status), fontWeight = FontWeight.Bold)
        patch.validation.problems.forEach { ProblemRow(it) }
        patch.validation.notes.forEach { BodyText(it) }
        BodyText("Decision: " + decision.label)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.showRepairPreview(patch.path) }) { Text("Preview") }
            when (decision) {
                FileDecision.APPLIED -> Unit
                FileDecision.ACCEPTED -> {
                    TextButton(enabled = !rs.busy, onClick = { vm.clearRepairDecision(patch.path) }) { Text("Undo accept") }
                    TextButton(enabled = !rs.busy, onClick = { vm.rejectRepair(patch.path) }) { Text("Reject") }
                }
                FileDecision.REJECTED -> TextButton(enabled = !rs.busy, onClick = { vm.clearRepairDecision(patch.path) }) { Text("Reconsider") }
                FileDecision.PENDING, FileDecision.ROLLED_BACK -> {
                    Button(enabled = !rs.busy && patch.validation.canApply, onClick = { vm.acceptRepair(patch.path) }) { Text("Accept") }
                    TextButton(enabled = !rs.busy, onClick = { vm.rejectRepair(patch.path) }) { Text("Reject") }
                }
            }
        }
    }
}

@Composable
private fun ProblemRow(p: PatchProblem) {
    val color = if (p.severity == ProblemSeverity.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Text((if (p.severity == ProblemSeverity.ERROR) "Problem: " else "Note: ") + p.message + (p.line?.let { " (line $it)" } ?: ""), color = color, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun statusColor(s: ValidationStatus): Color = when (s) {
    ValidationStatus.PASSED -> MaterialTheme.almus.success
    ValidationStatus.WARNINGS -> MaterialTheme.colorScheme.tertiary
    ValidationStatus.BLOCKED -> MaterialTheme.colorScheme.error
    ValidationStatus.NOT_CHECKED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Asking the AI for a patch uses the same confirm-first request as the other tabs; the answer only adds suggestions. */
@Composable
private fun AiPatchCard(vm: DebugViewModel, ai: AiUi, note: String?, rs: RepairState, state: DebugState) {
    val busy = ai is AiUi.Preparing || ai is AiUi.Working
    SectionCard("Ask the AI for a patch") {
        BodyText(note ?: "The AI only suggests changes. You see what would be sent and confirm first; the suggestion is checked against your real files and appears here as a patch you can accept or reject.")
        if (note == null) {
            Button(enabled = !busy && !state.diagnosis.isEmpty, onClick = { vm.prepareAi(AiTask.GENERATE_PATCH, includeCode = true) }) { Text("Ask the AI for a patch") }
        }
        when (ai) {
            AiUi.Idle -> Unit
            AiUi.Preparing -> { BodyText("Preparing the request..."); TextButton(onClick = vm::cancelAi) { Text("Cancel") } }
            is AiUi.Working -> { BodyText("Waiting for the answer... Your project is not changed while you wait."); TextButton(onClick = vm::cancelAi) { Text("Cancel") } }
            is AiUi.Confirm -> BodyText("Waiting for your confirmation.")
            is AiUi.Done -> {
                when (val o = ai.outcome) {
                    is AiOutcome.Answer -> {
                        BodyText("${o.response.patches.size - o.rejectedPatches.size} patch(es) offered, ${o.rejectedPatches.size} did not match your files.")
                        Button(onClick = { vm.prepareRepair(rs.plan?.issueId) }) { Text("Update the repair plan") }
                    }
                    is AiOutcome.Unavailable -> BodyText(o.reason)
                    is AiOutcome.Failed -> BodyText(o.message)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ai.outcome is AiOutcome.Failed) Button(onClick = vm::retryAi) { Text("Retry") }
                    TextButton(onClick = vm::cancelAi) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(vm: DebugViewModel, rs: RepairState) {
    val applied = RepairLogs.appliedRepairs(rs.log)
    SectionCard("Repair record") {
        if (rs.log.isEmpty()) {
            BodyText("Nothing has been applied, rejected or rolled back yet.")
            return@SectionCard
        }
        applied.take(MAX_LISTED_REPAIRS).forEach { a ->
            Text(formatTime(a.atMillis) + "  " + a.issueSummary.take(80), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            BodyText(a.paths.joinToString())
            if (a.rolledBack) BodyText("Rolled back.") else OutlinedButton(enabled = !rs.busy, onClick = { vm.rollbackRepair(a.applyId) }) { Text("Roll back") }
        }
        Text("Every change", style = MaterialTheme.typography.labelMedium)
        rs.log.take(MAX_LISTED_LOG).forEach { e ->
            Text(
                "${formatTime(e.atMillis)}  ${e.event.label}  ${e.path}  (${e.origin.lowercase()}, +${e.added} -${e.removed})",
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun RepairPreviewDialog(
    preview: RepairPreview,
    patch: RepairFilePatch?,
    decision: FileDecision,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
) {
    var mode by remember { mutableIntStateOf(0) }
    val modes = listOf("Changes", "Side by side", "Original", "Modified")
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(preview.path.substringAfterLast('/')) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(preview.path, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Text("+${preview.added} / -${preview.removed} lines in ${preview.hunks} place(s)", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    modes.forEachIndexed { i, label ->
                        if (mode == i) Button(onClick = { mode = i }) { Text(label) } else OutlinedButton(onClick = { mode = i }) { Text(label) }
                    }
                }
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    when (mode) {
                        0 -> UnifiedView(preview)
                        1 -> SideBySideView(preview)
                        2 -> FullTextView(preview.originalText)
                        else -> FullTextView(preview.modifiedText)
                    }
                }
            }
        },
        confirmButton = {
            val canAccept = patch != null && patch.validation.canApply && decision != FileDecision.APPLIED && decision != FileDecision.ACCEPTED
            TextButton(onClick = onAccept, enabled = canAccept) { Text("Accept") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReject, enabled = decision != FileDecision.APPLIED) { Text("Reject") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

@Composable
private fun UnifiedView(preview: RepairPreview) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        preview.unified.forEach { d ->
            if (d == null) {
                Text("   ...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            } else {
                val (prefix, color) = when (d.kind) {
                    DiffKind.REMOVED -> "- " to MaterialTheme.colorScheme.error
                    DiffKind.ADDED -> "+ " to MaterialTheme.almus.success
                    DiffKind.CONTEXT -> "  " to MaterialTheme.colorScheme.onSurfaceVariant
                }
                val oldNo = d.oldLine?.toString().orEmpty().padStart(4)
                val newNo = d.newLine?.toString().orEmpty().padStart(4)
                Text("$oldNo $newNo $prefix${d.text}", color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SideBySideView(preview: RepairPreview) {
    preview.sideBySide.forEach { row ->
        if (row == null) {
            Text("...", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        } else {
            val oldColor = if (row.kind == SideKind.REMOVED || row.kind == SideKind.CHANGED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            val newColor = if (row.kind == SideKind.ADDED || row.kind == SideKind.CHANGED) MaterialTheme.almus.success else MaterialTheme.colorScheme.onSurfaceVariant
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text((row.oldLine?.toString() ?: "") + " " + (row.oldText ?: ""), color = oldColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text((row.newLine?.toString() ?: "") + " " + (row.newText ?: ""), color = newColor, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FullTextView(text: String) {
    val lines = text.replace("\r\n", "\n").split('\n')
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        lines.take(MAX_FULL_TEXT_LINES).forEachIndexed { i, line ->
            Text((i + 1).toString().padStart(4) + " " + line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        if (lines.size > MAX_FULL_TEXT_LINES) Text("... ${lines.size - MAX_FULL_TEXT_LINES} more line(s) not shown", style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatTime(millis: Long): String =
    try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis)) } catch (e: Exception) { millis.toString() }

private const val MAX_LISTED_ERRORS = 5
private const val MAX_LISTED_REPAIRS = 10
private const val MAX_LISTED_LOG = 20
private const val MAX_FULL_TEXT_LINES = 600
