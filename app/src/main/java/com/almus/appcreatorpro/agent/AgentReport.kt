package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.ChangeKind
import com.almus.appcreatorpro.ai.ChangeSet
import com.almus.appcreatorpro.debug.Severity

enum class ReportOutcome { READY, NEEDS_ATTENTION, NOTHING_TO_APPLY }

/** Step 10. [markdown] is what the user reads (and can copy); the numbers feed the summary line on the screen. */
data class AgentReport(
    val markdown: String,
    val outcome: ReportOutcome,
    val errors: Int,
    val warnings: Int,
    val filesCreated: Int,
    val filesChanged: Int,
)

object AgentReportBuilder {
    private fun cell(s: String) = s.replace('|', '/').replace('\n', ' ').take(120)

    fun build(state: AgentRunState, set: ChangeSet?, nowMillis: Long): AgentReport {
        val created = set?.changes?.count { it.kind == ChangeKind.CREATE } ?: 0
        val changed = set?.changes?.count { it.kind == ChangeKind.MODIFY } ?: 0
        val errors = (state.validation?.errors?.size ?: 0) + (state.testOutcome?.problems?.count { it.severity == Severity.ERROR } ?: 0)
        val warnings = (state.validation?.warningCount ?: 0) + (state.testOutcome?.problems?.count { it.severity == Severity.WARNING } ?: 0)
        val failedTasks = state.tasks.count { it.status == TaskStatus.FAILED || it.status == TaskStatus.SKIPPED }
        val notReady = state.readiness?.ready == false
        val outcome = when {
            set == null -> ReportOutcome.NOTHING_TO_APPLY
            errors > 0 || failedTasks > 0 || notReady -> ReportOutcome.NEEDS_ATTENTION
            else -> ReportOutcome.READY
        }

        val md = StringBuilder()
        md.append("# Agent completion report\n\n")
        md.append("**Goal:** ").append(state.goal.take(400)).append("\n\n")
        md.append("**Result:** ").append(
            when (outcome) {
                ReportOutcome.READY -> "Ready for your review: $created new file(s), $changed changed file(s)."
                ReportOutcome.NEEDS_ATTENTION -> "Review needed: $created new file(s), $changed changed file(s), but $errors error(s), $failedTasks unfinished task(s)" + if (notReady) " and build checks that failed." else "."
                ReportOutcome.NOTHING_TO_APPLY -> "The agent produced no file changes."
            },
        ).append("\n\n")
        md.append("**Nothing has been written to your project.** The files below are a proposal; they change your project only when you apply them, and applying can be undone from History.\n\n")

        if (state.plan != null) {
            md.append("## Tasks\n\n| # | Task | Kind | Status | Files |\n|---|---|---|---|---|\n")
            for (t in state.tasks) md.append("| ${t.id} | ${cell(t.title)} | ${t.kind.label} | ${t.status.name.lowercase()} | ${cell(t.touched.joinToString())} |\n")
            val problemTasks = state.tasks.filter { (it.status == TaskStatus.FAILED || it.status == TaskStatus.SKIPPED) && it.note.isNotBlank() }
            if (problemTasks.isNotEmpty()) {
                md.append("\nUnfinished:\n")
                problemTasks.forEach { md.append("- ${it.id} ${cell(it.title)}: ${it.note.take(200)}\n") }
            }
            md.append('\n')
        }

        if (set != null) {
            md.append("## Files\n\n")
            for (c in set.changes) md.append("- ${if (c.kind == ChangeKind.CREATE) "NEW" else "CHANGED"} `${c.path}` (+${c.added} -${c.removed})\n")
            md.append('\n')
        }

        if (state.recommendedDependencies.isNotEmpty()) {
            md.append("## Recommended dependencies\n\n")
            state.recommendedDependencies.forEach { md.append("- `${it.coordinate}`").append(if (it.reason.isNotBlank()) " - ${it.reason}" else "").append('\n') }
            md.append('\n')
        }

        md.append("## Checks\n\n")
        val v = state.validation
        if (v == null) md.append("- Code checks did not run.\n") else {
            md.append("- Static code checks over ${v.filesChecked} file(s): $errors error(s), $warnings warning(s). These are source-level heuristics, not a compiler.\n")
            v.errors.take(10).forEach { md.append("  - `${it.path}`${it.line?.let { l -> ":$l" } ?: ""}: ${it.message.take(160)}\n") }
        }
        state.testOutcome?.let { md.append("- Tests: ${it.summary}\n") }
        md.append('\n')

        if (state.corrections.isNotEmpty()) {
            md.append("## Corrections\n\n")
            for (c in state.corrections) {
                md.append("- Round ${c.round}: ${c.files.joinToString()} (errors ${c.errorsBefore} -> ${c.errorsAfter})").append(if (c.undone) " - made things worse, so it was undone" else "").append('\n')
            }
            md.append('\n')
        }

        state.readiness?.let { r ->
            md.append("## Build readiness\n\n")
            r.items.forEach { md.append("- [${it.status.name}] ${it.label}").append(if (it.detail.isNotBlank()) ": ${it.detail}" else "").append('\n') }
            md.append("\nNext: apply the files, then start a cloud build from the project's Cloud build screen. Applying does not start a build.\n\n")
        }

        if (state.rejections.isNotEmpty()) {
            md.append("## Changes the agent was not allowed to make\n\n")
            state.rejections.take(30).forEach { md.append("- `${it.path}`: ${it.reason}\n") }
            md.append('\n')
        }

        val seconds = ((state.finishedAtMillis ?: nowMillis) - state.startedAtMillis).coerceAtLeast(0) / 1000
        md.append("## Run\n\n- AI requests: ${state.modelCalls}\n- Duration: ${seconds}s\n")
        return AgentReport(md.toString(), outcome, errors, warnings, created, changed)
    }
}
