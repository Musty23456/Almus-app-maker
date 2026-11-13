package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.agent.StagedProjectFiles
import com.almus.appcreatorpro.ai.ProposedChange
import com.almus.appcreatorpro.debug.CodeEdit
import com.almus.appcreatorpro.debug.DiagnosedIssue
import com.almus.appcreatorpro.debug.DiffKind
import com.almus.appcreatorpro.debug.FixOrigin
import com.almus.appcreatorpro.debug.FixProposal
import com.almus.appcreatorpro.debug.FixScope
import com.almus.appcreatorpro.debug.Fingerprint
import com.almus.appcreatorpro.debug.LocationBasis
import com.almus.appcreatorpro.debug.TextEdits
import com.almus.appcreatorpro.ai.AiProjectFiles
import kotlinx.coroutines.CancellationException

/** Which of the pending suggestions belong to a diagnosed error. */
object RepairCandidates {
    /**
     * A suggestion belongs to the error when it was made for it (same issue id, or one of the issues merged into it),
     * or when it changes a project file the diagnosis names. Nothing else is pulled in.
     */
    fun select(issue: DiagnosedIssue, pending: List<FixProposal>): List<FixProposal> {
        val ids = HashSet<String>()
        ids += issue.id
        ids += issue.mergedIssueIds
        val paths = issue.affectedFiles.filter { it.inProject }.map { it.path }.toHashSet()
        return pending.filter { p -> (p.issueId != null && p.issueId in ids) || p.path in paths }.distinctBy { it.id }
    }
}

/** Step 2 of the workflow: which project files are involved, from the diagnosis, checked against the real file list. */
object AffectedFileResolver {
    fun resolve(issue: DiagnosedIssue, candidates: List<FixProposal>, known: Set<String>): List<RepairTarget> {
        val out = LinkedHashMap<String, RepairTarget>()
        for (a in issue.affectedFiles) {
            if (!a.inProject || a.path in out) continue
            out[a.path] = RepairTarget(a.path, a.line, a.basis, a.path in known, a.note)
        }
        for (p in candidates) {
            if (p.path in out) continue
            out[p.path] = RepairTarget(p.path, null, LocationBasis.INFERRED, p.path in known, "Named by the suggested change, not by the build log.")
        }
        // Files the log states come first, guesses last.
        return out.values.sortedBy { it.basis.ordinal }
    }
}

/**
 * Turns a diagnosed error and the suggestions made for it into a [RepairPlan]:
 *   1. choose the suggestions that belong to the error,
 *   2. identify the project files involved,
 *   3. READ each original file (from the real project, never from the overlay),
 *   4. build the smallest change: only the suggested line edits, merged when they do not overlap,
 *   5. stage the result in a [StagedProjectFiles] overlay (the real project is only read here),
 *   6. validate every patched file and measure what changed.
 *
 * It writes nothing to the project and creates no change of its own: with no suggestion there is no patch.
 */
class RepairEngine(
    private val base: AiProjectFiles,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private class Source(val used: List<FixProposal>)

    suspend fun plan(issue: DiagnosedIssue, pending: List<FixProposal>): RepairPlan {
        val now = clockMillis()
        val known = base.list().toHashSet()
        val candidates = RepairCandidates.select(issue, pending)
        val targets = AffectedFileResolver.resolve(issue, candidates, known)
        val skipped = ArrayList<SkippedPatch>()
        val notices = ArrayList<String>()
        val staged = StagedProjectFiles(base)
        val sources = LinkedHashMap<String, Source>()

        for ((path, group) in candidates.groupBy { it.path }) {
            if (path !in known) {
                for (p in group) skipped += SkippedPatch(p.title, path, "The file is not in the project (any more).")
                continue
            }
            // Read the ORIGINAL file before anything is edited.
            val original = try {
                base.read(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                for (p in group) skipped += SkippedPatch(p.title, path, "The file could not be read.")
                continue
            }
            val originalHash = TextEdits.hash(original)
            var merged: List<CodeEdit> = emptyList()
            var patched: String? = null
            val used = ArrayList<FixProposal>()
            // Rule-based suggestions first, then the AI's, most confident first: deterministic and the safer one wins an overlap.
            val ordered = group.sortedWith(compareBy<FixProposal>({ it.origin.ordinal }, { it.confidence.ordinal }, { it.id }))
            for (p in ordered) {
                if (p.baseHash != originalHash) {
                    skipped += SkippedPatch(p.title, path, "The file changed after this suggestion was made. Run the checks again.")
                    continue
                }
                val trial = merged + p.edits
                when (val r = TextEdits.apply(original, trial)) {
                    is TextEdits.Result.Invalid -> skipped += SkippedPatch(p.title, path, if (used.isEmpty()) r.reason else "It cannot be combined with another change to the same file: ${r.reason}")
                    is TextEdits.Result.Ok -> { merged = trial; patched = r.text; used += p }
                }
            }
            val text = patched
            if (text == null || used.isEmpty()) continue
            if (text == original) {
                for (p in used) skipped += SkippedPatch(p.title, path, "It does not change anything.")
                continue
            }
            staged.write(path, text) // overlay only
            sources[path] = Source(used)
        }

        val planId = Fingerprint.of("repair", issue.id, now, sources.keys.joinToString("|"))
        val resolved = staged.buildChangeSet(planId, "Repair: ${issue.issue.message.take(60)}", issue.issue.message.take(200), now)
        for (r in resolved.rejected) {
            val src = sources[r.path]
            skipped += SkippedPatch(src?.used?.firstOrNull()?.title ?: "Change to ${r.path}", r.path, r.reason)
        }

        val patches = ArrayList<RepairFilePatch>()
        for (change in resolved.changeSet?.changes.orEmpty()) {
            val src = sources[change.path] ?: continue
            patches += buildPatch(change, src.used, targets)
        }

        if (patches.isEmpty()) notices += RepairText.NO_PATCH
        if (issue.scope == FixScope.ENVIRONMENT) {
            notices += "This kind of error is usually caused by the build environment (network, runner, installed tools), not by your code. A code patch may not help."
        }
        for (a in issue.affectedFiles) {
            if (!a.inProject) notices += "The log also names \"${a.path}\", which is not a file of this project. It is never changed."
        }
        if (!issue.confidence.sufficient) {
            notices += "The diagnosis has insufficient evidence for a specific solution. Treat every patch below with extra care."
        }

        return RepairPlan(
            id = planId,
            issueId = issue.id,
            issueTitle = issue.issue.message.take(200),
            diagnosisNote = issue.confidence.level.label + " confidence (" + issue.confidence.score + "/100)" + if (issue.confidence.sufficient) "" else ", insufficient evidence",
            targets = targets,
            patches = patches,
            skipped = skipped,
            notices = notices.distinct(),
            createdAtMillis = now,
        )
    }

    private fun buildPatch(change: ProposedChange, used: List<FixProposal>, targets: List<RepairTarget>): RepairFilePatch {
        val title = used.map { it.title }.distinct().joinToString("; ").take(160)
        val explanation = used.map { it.explanation.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ").take(600)
        val origin = if (used.any { it.origin == FixOrigin.AI }) FixOrigin.AI else FixOrigin.RULE
        val validation = PatchValidator.validate(change.path, change.before, change.after)
        val reported = targets.firstOrNull { it.path == change.path && it.line != null }?.line
        var near: Boolean? = null
        var note: String? = null
        if (reported != null) {
            val changedLines = change.diff.filter { it.kind != DiffKind.CONTEXT }.mapNotNull { it.oldLine ?: it.newLine }
            if (changedLines.isNotEmpty()) {
                near = changedLines.any { kotlin.math.abs(it - reported) <= NEAR_LINES }
                note = if (near == true) "Changes lines near line $reported, where the log reports the error."
                else "The log reports line $reported, but the patch changes lines ${changedLines.minOrNull() ?: reported}-${changedLines.maxOrNull() ?: reported}. Check that it addresses this error."
            }
        }
        return RepairFilePatch(
            path = change.path, change = change.copy(explanation = explanation), title = title, explanation = explanation, origin = origin,
            proposalIds = used.map { it.id }, validation = validation, nearReportedLine = near, locationNote = note,
        )
    }

    companion object {
        const val NEAR_LINES = 5
    }
}
