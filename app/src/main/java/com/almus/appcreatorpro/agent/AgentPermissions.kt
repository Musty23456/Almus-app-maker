package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.ChangeResolver
import com.almus.appcreatorpro.ai.GeneratedFileValidator
import com.almus.appcreatorpro.ai.RawChange
import com.almus.appcreatorpro.ai.SecretScanner

/**
 * What the agent is allowed to do. The limits are plain numbers so they can be tested; the rules that matter most
 * (never delete, never write into the project, never blind-overwrite) are code and have no switch.
 */
data class AgentPermissions(
    val maxTasks: Int = 15,
    val maxStagedFiles: Int = 60,
    val maxStagedBytes: Int = 2 * 1024 * 1024,
    val maxFileBytes: Int = 256 * 1024,
    val maxToolRoundsPerTask: Int = 3,
    val maxFilesReadPerTask: Int = 12,
    val maxTransportRetries: Int = 2,
    val maxParseRetries: Int = 1,
    val maxCorrectionRounds: Int = 2,
    val maxModelCalls: Int = 60,
    /** The only tools the model may call. All of them are read-only. */
    val allowedTools: Set<String> = setOf(AgentToolbox.LIST_FILES, AgentToolbox.READ_FILE, AgentToolbox.SEARCH),
) {
    /** The agent never deletes anything. Not a setting. */
    val canDelete: Boolean get() = false
}

object AgentGuard {
    /** Null = the path may be written. Same rules as generated projects: no traversal, no tool folders, no credentials, no binaries. */
    fun checkPath(path: String): String? = GeneratedFileValidator.checkPath(path)

    /** Null = the new text may be staged. A secret that was already in the file is the user's business; a NEW one is refused. */
    fun checkNewText(before: String?, after: String, perms: AgentPermissions): String? {
        if (after.isBlank()) return "The new file would be empty."
        if (after.indexOf('\u0000') >= 0) return "The new text contains binary data."
        if (after.toByteArray(Charsets.UTF_8).size > perms.maxFileBytes) return "The new file is too large (limit ${perms.maxFileBytes / 1024} KB)."
        if (SecretScanner.containsSecret(after) && (before == null || !SecretScanner.containsSecret(before))) {
            return "The new text contains what looks like a secret. Secrets are never written by the AI."
        }
        return null
    }
}

data class StagingReport(val applied: List<String>, val rejected: List<AgentRejection>)

/**
 * Puts what the model proposed into the staging area (never into the project). Every change is checked here:
 *  - no delete, no unsafe path, no secret, no oversized or empty file, limits on count and total size;
 *  - an EXISTING file is only changed when the model was shown it (no blind overwrite), and a file that was shown only
 *    in part (cut for length) can only be changed with targeted edits.
 */
class StagingApplier(private val staged: StagedProjectFiles, private val perms: AgentPermissions) {

    suspend fun apply(changes: List<RawChange>, readable: Set<String>, truncated: Set<String>, taskId: String?): StagingReport {
        val applied = ArrayList<String>()
        val rejected = ArrayList<AgentRejection>()
        val seen = HashSet<String>()
        fun no(path: String, reason: String) { rejected += AgentRejection(path, reason, taskId) }

        for (raw in changes) {
            val path = raw.path.trim()
            if (raw.action == "delete" || raw.action == "remove") { no(path, "The agent never deletes files."); continue }
            if (!seen.add(path.lowercase())) { no(path, "The same file appears twice in one answer."); continue }
            val pathProblem = AgentGuard.checkPath(path)
            if (pathProblem != null) { no(path, pathProblem); continue }
            val exists = staged.exists(path)
            if (!exists && !staged.isStaged(path) && staged.stagedPaths().size >= perms.maxStagedFiles) { no(path, "Too many files (limit ${perms.maxStagedFiles})."); continue }
            val before: String? = if (exists) {
                try { staged.read(path) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { no(path, "The current file could not be read."); continue }
            } else null
            if (before != null && path !in readable) { no(path, "The file was not shown to the AI first, so it is not overwritten blindly."); continue }

            val newText: String = when {
                raw.content != null -> {
                    if (before != null && path in truncated) { no(path, "The file was too long to be shown completely; only targeted edits may change it."); continue }
                    raw.content
                }
                raw.edits.isNotEmpty() -> {
                    if (before == null) { no(path, "Targeted edits need an existing file."); continue }
                    when (val r = ChangeResolver.applyEdits(before, raw.edits)) {
                        is ChangeResolver.EditApply.Ok -> r.text
                        is ChangeResolver.EditApply.Bad -> { no(path, r.reason); continue }
                    }
                }
                else -> { no(path, "The change has neither new content nor edits."); continue }
            }
            if (before != null && newText == before) { no(path, "Nothing changes in this file."); continue }
            val textProblem = AgentGuard.checkNewText(before, newText, perms)
            if (textProblem != null) { no(path, textProblem); continue }
            val newBytes = newText.toByteArray(Charsets.UTF_8).size
            val oldBytes = staged.stagedText(path)?.toByteArray(Charsets.UTF_8)?.size ?: 0
            if (staged.totalStagedBytes() - oldBytes + newBytes > perms.maxStagedBytes) { no(path, "The agent's total output would exceed ${perms.maxStagedBytes / (1024 * 1024)} MB."); continue }
            staged.write(path, newText)
            applied += path
        }
        return StagingReport(applied, rejected)
    }
}
