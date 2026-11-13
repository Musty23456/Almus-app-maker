package com.almus.appcreatorpro.debug

/**
 * Phase 22: what the user reads. The report always has the eight sections
 *
 *   Error Summary, Error Category, Root Cause, Affected Files, Technical Explanation,
 *   Recommended Solution, Suggested Code Changes, Confidence Level
 *
 * and can be built in two ways:
 *  - [DiagnosisReports.local]: from the rule-based [Diagnosis] alone (works offline, no AI),
 *  - [DiagnosisReports.withAi]: the same report with the AI's answer merged in. Whatever the AI claims is checked or
 *    labelled: file paths against the project's real files, patches against the real file text, its confidence against
 *    the local evidence ([ConfidenceCalibrator]).
 */
enum class ChangeSource(val label: String) {
    RULE_FIX("Rule-based fix: preview and apply it from the Fixes tab"),
    AI_PATCH("AI patch, checked against your file: preview and apply it from the Fixes tab"),
    AI_SUGGESTION("AI suggestion: NOT checked against your files, review it before using it"),
}

/** [path] and [line] are set only for a file that really exists in the project, so the screen can offer "Open". */
data class ReportFile(val label: String, val detail: String, val verified: Boolean, val path: String? = null, val line: Int? = null)

data class ReportCodeChange(val path: String?, val description: String, val code: String?, val source: ChangeSource)

data class ReportConfidence(
    val level: AnswerConfidence,
    /** 0..100 from the local analysis when there is one; null for an AI-only statement. */
    val score: Int?,
    val reasons: List<String>,
    /** Set when the confidence the AI stated was lowered, or when the AI gave none. */
    val note: String?,
)

data class DiagnosisReport(
    val errorSummary: String,
    val errorCategory: String,
    val rootCause: String,
    val affectedFiles: List<ReportFile>,
    val technicalExplanation: String,
    val recommendedSolution: String,
    val codeChanges: List<ReportCodeChange>,
    val confidence: ReportConfidence,
    val secondaryErrors: List<String>,
    val limitations: List<String>,
    val aiModel: String?,
    val aiAssisted: Boolean,
    val focusIssueId: String?,
) {
    fun toMarkdown(): String = buildString {
        fun section(title: String, body: String) { append("## ").append(title).append('\n').append(body.trim()).append("\n\n") }
        append("# Almus error diagnosis\n\n")
        section("Error Summary", errorSummary)
        section("Error Category", errorCategory)
        section("Root Cause", rootCause)
        section("Affected Files", if (affectedFiles.isEmpty()) "No file is named in the information available." else affectedFiles.joinToString("\n") { "- `${it.label}` (${it.detail})" })
        section("Technical Explanation", technicalExplanation)
        section("Recommended Solution", recommendedSolution)
        section(
            "Suggested Code Changes",
            if (codeChanges.isEmpty()) "No code change can be proposed from the information available."
            else codeChanges.joinToString("\n\n") { c ->
                buildString {
                    append("- ").append(c.description)
                    c.path?.let { append(" (`").append(it).append("`)") }
                    append("\n  _").append(c.source.label).append("_")
                    c.code?.let { append("\n\n```\n").append(it.trim()).append("\n```") }
                }
            },
        )
        section(
            "Confidence Level",
            buildString {
                append(confidence.level.label)
                confidence.score?.let { append(" (local score ").append(it).append("/100)") }
                confidence.note?.let { append("\n").append(it) }
                confidence.reasons.forEach { append("\n- ").append(it) }
            },
        )
        if (secondaryErrors.isNotEmpty()) section("Secondary Errors", secondaryErrors.joinToString("\n") { "- $it" })
        if (limitations.isNotEmpty()) section("What this analysis could not determine", limitations.joinToString("\n") { "- $it" })
        append("_Local rule-based analysis").append(if (aiAssisted) " + AI${aiModel?.let { " ($it)" } ?: ""}" else " (no AI used)").append(". It can be wrong; check it against your project._\n")
    }
}

object DiagnosisReports {
    private const val MAX_CODE = 800

    /** The report for [focus] (default: the problem to fix first), or null when the log holds nothing to diagnose. */
    fun local(diagnosis: Diagnosis, focus: DiagnosedIssue? = diagnosis.primary, fixes: List<FixProposal> = emptyList()): DiagnosisReport? {
        val f = focus ?: diagnosis.issues.firstOrNull() ?: return null
        val root: DiagnosedIssue? = f.link?.let { l -> diagnosis.issues.firstOrNull { it.id == l.rootIssueId } }
        val followers = diagnosis.secondaryOf(f)

        val summary = buildString {
            append(f.issue.message.trim().take(300))
            if (f.occurrences > 1) append(" (reported ${f.occurrences} times)")
            when (diagnosis.buildFailed) { true -> append(" The build failed."); false -> append(" The build succeeded."); null -> Unit }
        }
        val category = "${f.category.label} - severity: ${f.severity.label} - fix is most likely in: ${f.scope.label.replaceFirstChar { it.lowercase() }}"
        val rootCause = when {
            f.role == ErrorRole.SECONDARY && root != null ->
                "Probably caused by another error: \"${root.issue.message.trim().take(200)}\". ${f.link!!.reason} (${f.link.confidence.name.lowercase()} confidence in this link)."
            f.role == ErrorRole.INFORMATIONAL -> "This is a warning or a summary line, not a failure. It has no root cause of its own."
            !f.confidence.sufficient -> "Not determined. The log shows that something failed but does not contain enough information to say why."
            followers.isNotEmpty() -> "This error is a root cause: nothing earlier in the log explains it, and ${followers.size} other error(s) most likely follow from it."
            else -> "This error is a root cause: nothing else in the log explains it."
        }
        val files = f.affectedFiles.map { ReportFile(it.label, detailOf(it), it.inProject && it.basis != LocationBasis.INFERRED, it.path.takeIf { _ -> it.inProject }, it.line) }
        val tech = buildString {
            append(f.why ?: "There is no built-in explanation for this kind of message, so none is given. The message text above is the only evidence.")
            f.stackTrace?.let { t ->
                append("\n\nStack trace: ").append(t.outermost.simpleName)
                if (t.chain.size > 1) append(" caused by ").append(t.rootCause.simpleName)
                t.rootCause.message?.let { append(": ").append(it.take(200)) }
                val pf = t.firstProjectFrame
                if (pf != null) append(". The first frame in your code is ").append(pf.text).append('.')
                else append(". None of its frames is in your project, so it does not point at a line of your code.")
            }
        }
        val solution = when {
            f.role == ErrorRole.SECONDARY && root != null ->
                "Fix the root cause first (\"${root.issue.message.trim().take(120)}\"), then build again." + (f.solution?.let { " If this error is still there afterwards: $it" } ?: "")
            f.confidence.sufficient && f.solution != null -> f.solution
            f.solution != null -> "The log does not contain enough information for a specific fix. General guidance: ${f.solution}"
            else -> "No solution can be given from the information available."
        }
        val changes = fixes.filter { it.issueId == f.id || it.issueId in f.mergedIssueIds }.map { p ->
            ReportCodeChange(p.path, p.title, previewOf(p), if (p.origin == FixOrigin.AI) ChangeSource.AI_PATCH else ChangeSource.RULE_FIX)
        }
        return DiagnosisReport(
            errorSummary = summary, errorCategory = category, rootCause = rootCause, affectedFiles = files, technicalExplanation = tech,
            recommendedSolution = solution, codeChanges = changes,
            confidence = ReportConfidence(localLevel(f.confidence), f.confidence.score, f.confidence.reasons, null),
            secondaryErrors = followers.map { "${it.issue.message.trim().take(160)}${it.affectedFiles.firstOrNull { a -> a.inProject }?.let { a -> " (${a.label})" } ?: ""}" },
            limitations = diagnosis.limitations, aiModel = null, aiAssisted = false, focusIssueId = f.id,
        )
    }

    /**
     * Merges an AI answer into [local]. [projectPaths] is the project's real file list (null = unknown: then no AI path is
     * called verified). [offered] are the AI patches that survived the check against the files.
     */
    fun withAi(
        local: DiagnosisReport,
        response: AiDebugResponse,
        localConfidence: DiagnosticConfidence?,
        projectPaths: Collection<String>?,
        offered: List<FixProposal> = emptyList(),
        rejectedPatches: Int = 0,
    ): DiagnosisReport {
        val known = projectPaths?.map { it.replace('\\', '/').trimStart('/') }?.toHashSet()
        val aiFiles = response.affectedFiles.map { a ->
            val path = a.path.replace('\\', '/').trimStart('/')
            val exists = known?.contains(path)
            ReportFile(
                path + (a.line?.let { ":$it" } ?: ""),
                "named by the AI" + (a.note?.let { ": $it" } ?: "") + when (exists) { true -> "; the file exists in your project"; false -> "; NOT found in your project, so it may be wrong"; null -> "" },
                exists == true, path.takeIf { exists == true }, a.line,
            )
        }
        val files = (local.affectedFiles + aiFiles).distinctBy { it.label }
        val category = response.category?.takeIf { it.isNotBlank() && !local.errorCategory.contains(it, ignoreCase = true) }
            ?.let { "${local.errorCategory} (the AI calls it: $it)" } ?: local.errorCategory
        val changes = ArrayList<ReportCodeChange>()
        changes += offered.map { p -> ReportCodeChange(p.path, p.title, previewOf(p), ChangeSource.AI_PATCH) }
        changes += response.codeSuggestions.map { ReportCodeChange(it.path, it.description, it.code, ChangeSource.AI_SUGGESTION) }
        changes += local.codeChanges.filter { c -> c.source == ChangeSource.RULE_FIX }
        val limits = ArrayList<String>(local.limitations)
        if (response.missingInformation.isNotEmpty()) limits += "To be more certain the AI would need: " + response.missingInformation.joinToString("; ")
        if (rejectedPatches > 0) limits += "$rejectedPatches AI-suggested change(s) did not match your files and were not offered."
        val conf = ConfidenceCalibrator.calibrate(response.confidence, localConfidence, rootCauseNamed = response.rootCause != null)
        return local.copy(
            errorSummary = response.summary.ifBlank { local.errorSummary },
            errorCategory = category,
            rootCause = response.rootCause ?: ("The AI could not name a root cause from the information given. " + local.rootCause),
            affectedFiles = files,
            technicalExplanation = response.explanation.ifBlank { local.technicalExplanation },
            recommendedSolution = response.recommendedSolution ?: local.recommendedSolution,
            codeChanges = changes,
            confidence = conf,
            limitations = limits,
            aiModel = response.model,
            aiAssisted = true,
        )
    }

    internal fun localLevel(c: DiagnosticConfidence): AnswerConfidence =
        if (!c.sufficient && c.score < 40) AnswerConfidence.INSUFFICIENT else AnswerConfidence.of(c.level)

    private fun detailOf(a: AffectedFile): String = a.basis.label + (a.note?.let { ": $it" } ?: "")

    private fun previewOf(p: FixProposal): String? {
        val added = p.edits.flatMap { it.newLines }
        if (added.isEmpty()) return null
        val text = added.joinToString("\n")
        return if (text.length > MAX_CODE) text.take(MAX_CODE) + "\n..." else text
    }
}

/** Keeps the AI from sounding more certain than the evidence allows. */
object ConfidenceCalibrator {

    /**
     * The AI may know more than the rules (it sees code and can read the whole message), so its level may be ONE step
     * above the local one, never more. Local "insufficient evidence" counts as low. An AI that says "insufficient" is
     * always believed. An AI that names no root cause is capped at medium. An AI that states nothing is not given a level
     * of its own: the local level is shown, and the report says that the AI gave none.
     */
    fun calibrate(stated: AiConfidence?, local: DiagnosticConfidence?, rootCauseNamed: Boolean = true): ReportConfidence {
        val localLevel: AnswerConfidence? = local?.let { DiagnosisReports.localLevel(it) }
        val reasons = ArrayList<String>()
        local?.reasons?.let { reasons += it }
        stated?.reason?.let { reasons += "AI: $it" }
        if (stated == null) {
            return ReportConfidence(
                localLevel ?: AnswerConfidence.INSUFFICIENT, local?.score, reasons,
                if (localLevel != null) "The AI did not state how sure it is, so the level of the local analysis is shown." else "No confidence is available.",
            )
        }
        if (stated.level == AnswerConfidence.INSUFFICIENT) return ReportConfidence(AnswerConfidence.INSUFFICIENT, local?.score, reasons, "The AI says the information does not allow a diagnosis.")
        var ceiling = when (localLevel) {
            null -> AnswerConfidence.HIGH
            AnswerConfidence.INSUFFICIENT -> AnswerConfidence.MEDIUM
            AnswerConfidence.LOW -> AnswerConfidence.MEDIUM
            AnswerConfidence.MEDIUM, AnswerConfidence.HIGH -> AnswerConfidence.HIGH
        }
        if (!rootCauseNamed && ceiling.rank > AnswerConfidence.MEDIUM.rank) ceiling = AnswerConfidence.MEDIUM
        val level = if (stated.level.rank > ceiling.rank) ceiling else stated.level
        val note = if (level != stated.level) "The AI said \"${stated.level.label}\", lowered to \"${level.label}\" because the evidence in the log does not support more certainty." else null
        return ReportConfidence(level, local?.score, reasons, note)
    }
}

/** Plain-text forms of a [Diagnosis] for the AI prompts (compact, redacted data only). */
object DiagnosisText {
    private const val MAX_CHARS = 5_000
    private const val MAX_ROOTS = 5
    private const val MAX_FOLLOWERS = 4

    fun forAi(d: Diagnosis): String {
        if (d.isEmpty) return ""
        val sb = StringBuilder()
        sb.append("Build ").append(when (d.buildFailed) { true -> "failed"; false -> "succeeded"; null -> "result unknown" })
            .append(": ").append(d.rootCauses.size).append(" root cause(s), ").append(d.secondary.size).append(" secondary error(s).\n")
        d.rootCauses.take(MAX_ROOTS).forEachIndexed { i, r ->
            sb.append("Root cause ").append(i + 1).append(" [").append(r.category.label).append(", ").append(r.severity.label)
                .append(", local confidence ").append(r.confidence.level.name.lowercase()).append(if (r.confidence.sufficient) "" else ", insufficient evidence").append("]: ")
                .append(r.issue.message.take(300))
            r.affectedFiles.firstOrNull()?.let { sb.append(" (").append(it.label).append(", ").append(it.basis.label).append(")") }
            if (r.occurrences > 1) sb.append(" [").append(r.occurrences).append(" occurrences]")
            sb.append('\n')
            r.why?.let { sb.append("  Local explanation: ").append(it.take(400)).append('\n') }
            d.secondaryOf(r).take(MAX_FOLLOWERS).forEach { s ->
                sb.append("  Likely follows from it: ").append(s.issue.message.take(200)).append(" (").append(s.link?.confidence?.name?.lowercase()).append(" confidence)\n")
            }
            r.stackTrace?.let { t -> sb.append("  Stack trace: ").append(t.chain.joinToString(" <- ") { it.simpleName }).append(t.firstProjectFrame?.let { "; first project frame ${it.text}" } ?: "; no frame in the project").append('\n') }
        }
        if (d.limitations.isNotEmpty()) {
            sb.append("Limits of the local analysis:\n")
            d.limitations.forEach { sb.append("- ").append(it.take(300)).append('\n') }
        }
        return sb.toString().take(MAX_CHARS)
    }

    /** What a follow-up question is asked about: the chosen issue (or the primary one) and what is known about it. */
    fun forFollowUp(d: Diagnosis, focus: DiagnosedIssue?): String {
        val base = forAi(d)
        val f = focus ?: return base
        val sb = StringBuilder(base)
        sb.append("\nThe question is about this error: ").append(f.issue.message.take(300)).append('\n')
        sb.append("Category ").append(f.category.label).append(", role ").append(f.role.label).append(", severity ").append(f.severity.label).append(".\n")
        f.affectedFiles.take(5).forEach { sb.append("File: ").append(it.label).append(" (").append(it.basis.label).append(")\n") }
        f.link?.let { sb.append("Local analysis thinks it follows from another error: ").append(it.reason.take(300)).append('\n') }
        return sb.toString().take(MAX_CHARS + 1_000)
    }
}
