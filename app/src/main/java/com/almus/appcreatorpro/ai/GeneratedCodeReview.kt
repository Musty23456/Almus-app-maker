package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.Fingerprint
import com.almus.appcreatorpro.repair.PatchProblem
import com.almus.appcreatorpro.repair.PatchValidation
import com.almus.appcreatorpro.repair.PatchValidator
import com.almus.appcreatorpro.repair.ProblemSeverity

enum class ReviewVerdict(val label: String) {
    /** The simple checks found nothing wrong. This is NOT a compile. */
    OK("No problems found by the simple checks"),
    WARNINGS("Check the preview before accepting"),

    /** Cannot be accepted: it would break the file in a way the checks can see. */
    BLOCKED("Blocked: it breaks a check"),
}

/** One file of a generated answer, with everything the user needs to decide: new or changed, size of the change, check results. */
data class ReviewedFile(
    val change: ProposedChange,
    val validation: PatchValidation,
    /** Problems in plain words, errors first. */
    val problems: List<String>,
    val notes: List<String>,
    val verdict: ReviewVerdict,
) {
    val path: String get() = change.path
    val isNew: Boolean get() = change.kind == ChangeKind.CREATE
    val canAccept: Boolean get() = verdict != ReviewVerdict.BLOCKED
}

data class CodeReview(
    val turnId: String,
    val files: List<ReviewedFile>,
    /** Code blocks that could not become a change, with the reason (nothing is hidden). */
    val rejected: List<RejectedChange>,
    val warnings: List<String>,
) {
    val acceptablePaths: List<String> get() = files.filter { it.canAccept }.map { it.path }
    val isEmpty: Boolean get() = files.isEmpty()
}

/**
 * Phase 24: "preview and approve generated code". A code answer from the chat is turned into real file changes
 * ([ChatApply]), checked with the Phase 23 syntax checks ([PatchValidator]), and shown file by file BEFORE anything is
 * offered as a change. The user picks the files; only those become a [ChangeSet] in the Changes tab, where a second,
 * explicit "Apply changes" writes them (and Undo can restore them). This object writes nothing.
 */
object GeneratedCodeReviewer {
    /** A new file that is much shorter than the one it replaces is probably a fragment, not the whole file. */
    private const val SHRINK_FRACTION = 0.5
    private const val SHRINK_MIN_LINES = 20

    /** Null when the answer holds no code block that names a file. */
    suspend fun review(turn: ChatTurn, files: AiProjectFiles, nowMillis: Long): CodeReview? {
        val resolved = ChatApply.changeSet(turn, files, nowMillis) ?: return null
        val set = resolved.changeSet ?: return CodeReview(turn.id, emptyList(), resolved.rejected, emptyList())
        return CodeReview(turn.id, set.changes.map { check(it) }, resolved.rejected, set.warnings)
    }

    fun check(change: ProposedChange): ReviewedFile {
        val v = PatchValidator.validate(change.path, change.before, change.after)
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        for (p in v.problems) {
            val line = p.line?.let { " (line $it)" }.orEmpty()
            when {
                // The repair rule "a patch must be small" does not fit new code: a big rewrite is shown as a warning, never blocked.
                p.severity == ProblemSeverity.ERROR && isSizeRule(p) -> warnings += "Large change: ${p.message.replace("That is a rewrite, not a minimal repair, and too much to review safely.", "Check the preview.").replace("That is a rewrite, not a minimal repair.", "Check the preview.")}"
                p.severity == ProblemSeverity.ERROR -> errors += p.message + line
                else -> warnings += p.message + line
            }
        }
        val before = change.before
        if (before != null) {
            val beforeLines = lineCount(before)
            val afterLines = lineCount(change.after)
            if (beforeLines >= SHRINK_MIN_LINES && afterLines < beforeLines * SHRINK_FRACTION) {
                warnings += "The new text has $afterLines lines but the file has $beforeLines. It may be only a part of the file: accepting it would delete the rest."
            }
        }
        val verdict = when {
            errors.isNotEmpty() -> ReviewVerdict.BLOCKED
            warnings.isNotEmpty() -> ReviewVerdict.WARNINGS
            else -> ReviewVerdict.OK
        }
        return ReviewedFile(change, v, errors + warnings, v.notes, verdict)
    }

    private fun isSizeRule(p: PatchProblem): Boolean =
        p.message.startsWith("The patch changes ") && (p.message.contains("rewrite") || p.message.contains("lines"))

    private fun lineCount(text: String): Int = text.replace("\r\n", "\n").trimEnd('\n').split('\n').size

    /**
     * The files the user accepted, as a change set for the Changes tab. Blocked files and paths that are not in the review are
     * left out. Null when nothing is left.
     */
    fun changeSetFor(review: CodeReview, accepted: Set<String>, nowMillis: Long): ChangeSet? {
        val chosen = review.files.filter { it.path in accepted && it.canAccept }.map { it.change }
        if (chosen.isEmpty()) return null
        val id = Fingerprint.of("chat-review", review.turnId, nowMillis, chosen.joinToString("|") { it.path })
        return ChangeSet(id, "AI chat change", "From the AI chat (reviewed)", chosen, review.warnings, nowMillis)
    }
}
