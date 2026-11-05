package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiPatchEdit
import com.almus.appcreatorpro.debug.Fingerprint
import com.almus.appcreatorpro.debug.TextEdits
import com.almus.appcreatorpro.domain.repository.SourceFileRepository

enum class ChangeKind { CREATE, MODIFY }

/**
 * One file the AI wants to create or change. [after] is always the COMPLETE new text, so the preview, the apply step and
 * undo never depend on how the model described the change. [baseHash] is the hash of the file the change was made for
 * (null for a new file): applying to a file that has changed since is refused.
 */
data class ProposedChange(
    val path: String,
    val kind: ChangeKind,
    val explanation: String,
    val before: String?,
    val after: String,
    val baseHash: String?,
    val added: Int,
    val removed: Int,
) {
    val diff by lazy(LazyThreadSafetyMode.NONE) { LineDiff.diff(before, after) }
}

data class ChangeSet(
    val id: String,
    val title: String,
    val summary: String,
    val changes: List<ProposedChange>,
    val warnings: List<String>,
    val createdAtMillis: Long,
) {
    val paths: List<String> get() = changes.map { it.path }
}

data class RejectedChange(val path: String, val reason: String)

data class ResolveResult(val changeSet: ChangeSet?, val rejected: List<RejectedChange>)

/** Turns what a model answered into a [ChangeSet] checked against the real files. Never deletes, never "fixes up" a bad answer. */
object ChangeResolver {
    const val MAX_CHANGES = 30

    suspend fun fromProposal(proposal: EditProposal, files: AiProjectFiles, title: String, nowMillis: Long): ResolveResult {
        val rejected = ArrayList<RejectedChange>()
        val changes = ArrayList<ProposedChange>()
        val warnings = ArrayList<String>()
        val known = files.list().toHashSet()
        val seen = HashSet<String>()
        for (raw in proposal.changes) {
            if (changes.size >= MAX_CHANGES) { rejected += RejectedChange(raw.path, "Too many files in one change (limit $MAX_CHANGES)."); continue }
            val path = raw.path.trim()
            if (!seen.add(path.lowercase())) { rejected += RejectedChange(path, "The same file appears twice."); continue }
            val pathProblem = GeneratedFileValidator.checkPath(path)
            if (pathProblem != null) { rejected += RejectedChange(path, pathProblem); continue }
            val before = if (path in known) {
                try { files.read(path) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { rejected += RejectedChange(path, "The current file could not be read."); continue }
            } else null

            val newText: String = when {
                raw.content != null -> raw.content
                raw.edits.isNotEmpty() -> {
                    if (before == null) { rejected += RejectedChange(path, "Targeted edits need an existing file."); continue }
                    when (val r = applyEdits(before, raw.edits)) {
                        is EditApply.Ok -> r.text
                        is EditApply.Bad -> { rejected += RejectedChange(path, r.reason); continue }
                    }
                }
                else -> { rejected += RejectedChange(path, "The change has neither new content nor edits."); continue }
            }
            val built = build(path, before, newText, raw.explanation)
            when (built) {
                is Built.Ok -> changes += built.change
                is Built.Skip -> rejected += RejectedChange(path, built.reason)
            }
            if (raw.action == "create" && before != null) warnings += "\"$path\" already exists; it is shown as a change to the existing file."
            if (raw.action == "modify" && before == null) warnings += "\"$path\" does not exist yet; it is shown as a new file."
        }
        if (changes.isEmpty()) return ResolveResult(null, rejected)
        val id = Fingerprint.of("change", title, nowMillis, changes.joinToString("|") { it.path })
        return ResolveResult(ChangeSet(id, title, proposal.summary, changes, warnings, nowMillis), rejected)
    }

    /** All files of a generated project, as a [ChangeSet] against what the (new) project already contains. */
    suspend fun fromGenerated(project: GeneratedProject, files: AiProjectFiles, nowMillis: Long): ResolveResult {
        val known = files.list().toHashSet()
        val rejected = ArrayList<RejectedChange>()
        val changes = ArrayList<ProposedChange>()
        for (f in project.files) {
            val before = if (f.path in known) try { files.read(f.path) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null } else null
            when (val b = build(f.path, before, f.content, "")) {
                is Built.Ok -> changes += b.change
                is Built.Skip -> rejected += RejectedChange(f.path, b.reason)
            }
        }
        if (changes.isEmpty()) return ResolveResult(null, rejected)
        val id = Fingerprint.of("generated", project.projectName, nowMillis, changes.size)
        return ResolveResult(ChangeSet(id, "Generate ${project.projectName}", project.summary, changes, emptyList(), nowMillis), rejected)
    }

    private sealed interface Built {
        data class Ok(val change: ProposedChange) : Built
        data class Skip(val reason: String) : Built
    }

    private fun build(path: String, before: String?, rawAfter: String, explanation: String): Built {
        // Keep the file's own line endings so the preview shows only real changes.
        val after = if (before != null && before.contains("\r\n") && !rawAfter.contains('\r')) rawAfter.replace("\n", "\r\n") else rawAfter
        if (after.isBlank()) return Built.Skip("The new file would be empty.")
        if (after.indexOf('\u0000') >= 0) return Built.Skip("The new text contains binary data.")
        if (after.toByteArray(Charsets.UTF_8).size > SourceFileRepository.MAX_FILE_BYTES) return Built.Skip("The new file is too large.")
        if (before != null && after == before) return Built.Skip("Nothing changes in this file.")
        // A secret that was already in the file is the user's business; a NEW one coming from the model is refused.
        if (SecretScanner.containsSecret(after) && (before == null || !SecretScanner.containsSecret(before))) {
            return Built.Skip("The new text contains what looks like a secret. Secrets are never written by the AI.")
        }
        val stats = LineDiff.stats(LineDiff.diff(before, after))
        return Built.Ok(
            ProposedChange(
                path = path, kind = if (before == null) ChangeKind.CREATE else ChangeKind.MODIFY, explanation = explanation,
                before = before, after = after, baseHash = before?.let { TextEdits.hash(it) }, added = stats.added, removed = stats.removed,
            ),
        )
    }

    sealed interface EditApply {
        data class Ok(val text: String) : EditApply
        data class Bad(val reason: String) : EditApply
    }

    /** Every "find" must occur exactly once in the current text; otherwise the whole file change is refused. */
    fun applyEdits(original: String, edits: List<AiPatchEdit>): EditApply {
        var text = original
        for ((i, e) in edits.withIndex()) {
            if (e.find.isEmpty()) return EditApply.Bad("Edit ${i + 1} has no text to find.")
            val first = text.indexOf(e.find)
            if (first < 0) return EditApply.Bad("Edit ${i + 1}: the text to replace was not found; the file may differ from what the model saw.")
            if (text.indexOf(e.find, first + 1) >= 0) return EditApply.Bad("Edit ${i + 1}: the text to replace occurs more than once, so the edit is ambiguous.")
            text = text.substring(0, first) + e.replace + text.substring(first + e.find.length)
        }
        return EditApply.Ok(text)
    }
}
