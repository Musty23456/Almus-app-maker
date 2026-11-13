package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.ai.ProposedChange
import com.almus.appcreatorpro.debug.FixOrigin
import com.almus.appcreatorpro.debug.LocationBasis

/**
 * Phase 23: vocabulary of the code repair engine. Plain Kotlin (no Android classes), so it is unit-tested on a normal JVM.
 *
 * The repair engine never invents a change. A patch exists only when a rule-based check or the AI produced a real,
 * line-exact edit (`FixProposal`) that still matches the real file. The engine reads the original file, stages the
 * result in an overlay (`StagedProjectFiles`), validates it and shows it. The project is written ONLY when the user
 * accepts files and presses Apply, and even then no one claims the error is gone: that is known only after a rebuild.
 */

enum class ProblemSeverity { ERROR, WARNING }

/** One thing the validator found. [line] is a line of the PATCHED text when known. */
data class PatchProblem(val severity: ProblemSeverity, val message: String, val line: Int? = null)

enum class ValidationStatus(val label: String) {
    /** The simple syntax checks ran and found nothing new. This is NOT a compile. */
    PASSED("No new syntax problems found"),
    WARNINGS("Passed with warnings"),

    /** The patch introduces a problem. It cannot be accepted. */
    BLOCKED("Blocked: the patch breaks a check"),

    /** There is no checker for this kind of file. The patch can be applied, but nothing was verified. */
    NOT_CHECKED("Not checked (no checker for this file type)"),
}

data class PatchValidation(
    val status: ValidationStatus,
    val problems: List<PatchProblem>,
    /** What was actually run, in words. */
    val checks: List<String>,
    /** Good news and context that is not a problem (for example "removed 1 bracket problem that was there before"). */
    val notes: List<String>,
) {
    val canApply: Boolean get() = status != ValidationStatus.BLOCKED
    val errors: List<PatchProblem> get() = problems.filter { it.severity == ProblemSeverity.ERROR }
    val warnings: List<PatchProblem> get() = problems.filter { it.severity == ProblemSeverity.WARNING }
}

/** A file the diagnosis points at, checked against the project's real file list. */
data class RepairTarget(
    val path: String,
    val line: Int?,
    val basis: LocationBasis,
    /** True when the file is in the project right now. */
    val exists: Boolean,
    val note: String?,
)

/** A suggestion that could not become part of the plan, and why. Shown to the user: nothing is hidden. */
data class SkippedPatch(val title: String, val path: String, val reason: String)

enum class FileDecision(val label: String) {
    PENDING("Waiting for your decision"),
    ACCEPTED("Accepted (not written yet)"),
    REJECTED("Rejected"),
    APPLIED("Applied"),
    ROLLED_BACK("Rolled back"),
}

/** The complete new text of ONE file, with everything the user needs to decide about it. */
data class RepairFilePatch(
    val path: String,
    /** before / after / diff / hashes. [ProposedChange.before] is the ORIGINAL file text, read before staging. */
    val change: ProposedChange,
    val title: String,
    val explanation: String,
    val origin: FixOrigin,
    /** Ids of the `FixProposal`s that were merged into this patch. */
    val proposalIds: List<String>,
    val validation: PatchValidation,
    /** True/false: the changed lines are/are not near the line the log reports. Null: the log gave no line. */
    val nearReportedLine: Boolean?,
    val locationNote: String?,
) {
    val added: Int get() = change.added
    val removed: Int get() = change.removed
}

/** What the engine worked out for one diagnosed error. [patches] may be empty: then [notices] say why. */
data class RepairPlan(
    val id: String,
    val issueId: String,
    val issueTitle: String,
    /** How sure the diagnosis is, in words, including "insufficient evidence". */
    val diagnosisNote: String,
    val targets: List<RepairTarget>,
    val patches: List<RepairFilePatch>,
    val skipped: List<SkippedPatch>,
    val notices: List<String>,
    val createdAtMillis: Long,
) {
    val hasPatches: Boolean get() = patches.isNotEmpty()
    fun patch(path: String): RepairFilePatch? = patches.firstOrNull { it.path == path }
}

/** Where the workflow stands. The last step (rebuild and look at the result) is always the user's. */
enum class RepairPhase(val label: String) {
    NONE("No repair prepared"),
    PREPARING("Preparing"),

    /** Diagnosed and looked at, but no real change is available for it. */
    NO_PATCH("No patch available"),
    AWAITING_APPROVAL("Waiting for your approval"),

    /** Files were written. NOT verified: the error may still be there. */
    APPLIED_UNVERIFIED("Applied, not verified: rebuild to find out"),
}

data class RepairState(
    val phase: RepairPhase = RepairPhase.NONE,
    val plan: RepairPlan? = null,
    val decisions: Map<String, FileDecision> = emptyMap(),
    val message: String? = null,
    /** Newest first. */
    val log: List<RepairLogEntry> = emptyList(),
    val busy: Boolean = false,
) {
    fun decisionOf(path: String): FileDecision = decisions[path] ?: FileDecision.PENDING
    val acceptedPaths: List<String> get() = plan?.patches?.map { it.path }.orEmpty().filter { decisionOf(it) == FileDecision.ACCEPTED }
    val canApply: Boolean get() = !busy && acceptedPaths.isNotEmpty()
}

sealed interface RepairApplyOutcome {
    data class Applied(val applyId: String, val paths: List<String>, val proposalIds: List<String>) : RepairApplyOutcome
    data object NoPlan : RepairApplyOutcome
    data object NothingAccepted : RepairApplyOutcome

    /** An accepted file fails validation; nothing was written. */
    data class Blocked(val paths: List<String>) : RepairApplyOutcome

    /** A file changed after the patch was made. NOTHING was written. */
    data class Stale(val paths: List<String>) : RepairApplyOutcome

    /** The original files could not be saved to the backup folder, so NOTHING was written. */
    data class BackupFailed(val reason: String) : RepairApplyOutcome
    data class Failed(val reason: String) : RepairApplyOutcome
}

sealed interface RepairRollbackOutcome {
    data class RolledBack(val applyId: String, val paths: List<String>, val proposalIds: List<String>) : RepairRollbackOutcome

    /** The files were edited after the repair; rolling back would throw those edits away. NOTHING was written. */
    data class Conflict(val paths: List<String>) : RepairRollbackOutcome
    data object NotFound : RepairRollbackOutcome
    data object AlreadyRolledBack : RepairRollbackOutcome
    data class Failed(val reason: String) : RepairRollbackOutcome
}

/** Fixed wording, so the screen and the tests agree: the engine never says an error is fixed. */
object RepairText {
    const val NOT_VERIFIED =
        "Applying a patch does not prove the error is fixed. Rebuild the project: only the new build result shows whether it worked."
    const val NO_PATCH =
        "No suggested change exists for this error yet, so there is nothing to apply. You can ask the AI for a patch (you confirm what is sent first) or edit the file yourself."
    const val NOT_A_COMPILER =
        "The syntax checks are simple text checks (brackets, quotes, XML and JSON structure). They cannot find type or logic errors."
}
