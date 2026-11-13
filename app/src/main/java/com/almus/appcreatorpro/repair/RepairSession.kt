package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.ai.AiChangeManager
import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ApplyOutcome
import com.almus.appcreatorpro.ai.ChangeSet
import com.almus.appcreatorpro.ai.UndoOutcome
import com.almus.appcreatorpro.debug.DiagnosedIssue
import com.almus.appcreatorpro.debug.Fingerprint
import com.almus.appcreatorpro.debug.FixProposal
import com.almus.appcreatorpro.debug.TextEdits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The code repair workflow of ONE project:
 *
 *   Build failure -> diagnosis -> suggested repair -> patch generation -> validation -> USER APPROVAL -> apply -> rebuild
 *
 * This class owns the steps from "patch generation" to "apply". It never writes to the project except in [apply],
 * which only writes files the user accepted one by one, through the shared [AiChangeManager] (so the same staleness
 * check, all-or-nothing writing and persisted undo history as every other AI change apply). Before [apply] writes,
 * the original text of every file is saved in [backups]; every applied, rejected and rolled back file is recorded in
 * the repair log.
 *
 * It never says the error is fixed: after [apply] the phase is [RepairPhase.APPLIED_UNVERIFIED] until the user
 * rebuilds and looks at the new build result.
 */
class RepairSession(
    val projectId: String,
    private val files: AiProjectFiles,
    private val changes: AiChangeManager,
    private val logStore: RepairLogStore,
    private val backups: RepairBackupStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val engine = RepairEngine(files, clockMillis)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(RepairState())
    val state: StateFlow<RepairState> = _state.asStateFlow()

    /** Loads the saved repair record. A record that cannot be read is treated as empty; it never blocks repairs. */
    suspend fun load() {
        val entries = try {
            logStore.load(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        _state.update { it.copy(log = entries) }
    }

    // ---- steps 1-6: identify files, read originals, generate minimal patches, validate -------------------------------

    /**
     * Builds the plan for [issue] from the pending suggestions [pending]. Writes nothing. Returns null only when the
     * preparation itself failed (the message says so); a plan without patches is a normal result.
     */
    suspend fun prepare(issue: DiagnosedIssue, pending: List<FixProposal>): RepairPlan? = mutex.withLock {
        _state.update { it.copy(phase = RepairPhase.PREPARING, busy = true, message = null) }
        val plan = try {
            engine.plan(issue, pending)
        } catch (e: CancellationException) {
            _state.update { it.copy(phase = phaseFor(it), busy = false) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(phase = phaseFor(it), busy = false, message = "The repair could not be prepared (${e.javaClass.simpleName}). Nothing in your project was changed.") }
            return@withLock null
        }
        _state.update {
            it.copy(
                phase = if (plan.hasPatches) RepairPhase.AWAITING_APPROVAL else RepairPhase.NO_PATCH,
                plan = plan,
                decisions = plan.patches.associate { p -> p.path to FileDecision.PENDING },
                busy = false,
                message = null,
            )
        }
        plan
    }

    fun preview(path: String): RepairPreview? = _state.value.plan?.patch(path)?.let { RepairPreviews.of(it) }

    // ---- step 7: the user decides, file by file -----------------------------------------------------------------------

    /** Accepting only marks the file; nothing is written until [apply]. A blocked patch cannot be accepted. */
    fun accept(path: String): Boolean {
        val st = _state.value
        val patch = st.plan?.patch(path) ?: return false
        if (st.decisionOf(path) == FileDecision.APPLIED) return false
        if (!patch.validation.canApply) {
            post("The patch for $path breaks a check, so it cannot be accepted: " + patch.validation.errors.firstOrNull()?.message.orEmpty())
            return false
        }
        setDecision(path, FileDecision.ACCEPTED)
        return true
    }

    /** Accepts every file whose patch is not blocked and that has no decision yet. Returns how many. */
    fun acceptAllValid(): Int {
        val st = _state.value
        val plan = st.plan ?: return 0
        val toAccept = plan.patches.filter { st.decisionOf(it.path) == FileDecision.PENDING && it.validation.canApply }.map { it.path }
        if (toAccept.isEmpty()) return 0
        _state.update { s -> s.copy(decisions = s.decisions + toAccept.associateWith { FileDecision.ACCEPTED }) }
        return toAccept.size
    }

    /** Back to "waiting for your decision" (from accepted, rejected or rolled back). */
    fun clearDecision(path: String) {
        val st = _state.value
        if (st.plan?.patch(path) == null) return
        if (st.decisionOf(path) == FileDecision.APPLIED) return
        setDecision(path, FileDecision.PENDING)
    }

    /** Rejecting records the decision in the repair log. The file is never touched. */
    suspend fun reject(path: String): Boolean {
        val st = _state.value
        val plan = st.plan ?: return false
        val patch = plan.patch(path) ?: return false
        if (st.decisionOf(path) == FileDecision.APPLIED) return false
        setDecision(path, FileDecision.REJECTED)
        appendLog(listOf(entry(RepairEvent.REJECTED, plan, patch, null, clockMillis())))
        return true
    }

    suspend fun rejectAllPending(): Int {
        val st = _state.value
        val plan = st.plan ?: return 0
        val rejecting = plan.patches.filter { st.decisionOf(it.path) == FileDecision.PENDING || st.decisionOf(it.path) == FileDecision.ACCEPTED }
        if (rejecting.isEmpty()) return 0
        _state.update { s -> s.copy(decisions = s.decisions + rejecting.associate { it.path to FileDecision.REJECTED }) }
        val now = clockMillis()
        appendLog(rejecting.map { entry(RepairEvent.REJECTED, plan, it, null, now) })
        return rejecting.size
    }

    // ---- step 8: apply what the user accepted ---------------------------------------------------------------------

    /**
     * Writes the ACCEPTED files, and only those. Refuses (and writes nothing) when nothing is accepted, when an accepted
     * patch is blocked, when a file changed after the patch was made, or when the originals could not be saved first.
     */
    suspend fun apply(): RepairApplyOutcome = mutex.withLock {
        _state.update { it.copy(busy = true) }
        try {
            doApply()
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun doApply(): RepairApplyOutcome {
        val st = _state.value
        val plan = st.plan ?: return RepairApplyOutcome.NoPlan
        val chosen = plan.patches.filter { st.decisionOf(it.path) == FileDecision.ACCEPTED }
        if (chosen.isEmpty()) {
            post("Accept at least one file first. Nothing was changed.")
            return RepairApplyOutcome.NothingAccepted
        }
        val blocked = chosen.filter { !it.validation.canApply }
        if (blocked.isNotEmpty()) {
            post("Nothing was changed. These patches break a check: " + blocked.joinToString { it.path })
            return RepairApplyOutcome.Blocked(blocked.map { it.path })
        }
        val now = clockMillis()
        val applyId = Fingerprint.of("repair-apply", plan.id, chosen.joinToString("|") { it.path }, now)

        // Preserve the originals BEFORE writing anything.
        try {
            backups.save(projectId, applyId, chosen.associate { it.path to it.change.before.orEmpty() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = "The original files could not be saved (${e.message.orEmpty().take(100)}). Nothing was changed."
            post(reason)
            return RepairApplyOutcome.BackupFailed(reason)
        }

        val set = ChangeSet(
            id = applyId,
            title = "Repair: " + plan.issueTitle.take(60),
            summary = plan.issueTitle,
            changes = chosen.map { it.change },
            warnings = emptyList(),
            createdAtMillis = now,
        )
        changes.offer(set)
        return when (val r = changes.apply(applyId)) {
            is ApplyOutcome.Applied -> {
                appendLog(chosen.map { entry(RepairEvent.APPLIED, plan, it, applyId, now) })
                val paths = chosen.map { it.path }
                _state.update { s ->
                    s.copy(
                        phase = RepairPhase.APPLIED_UNVERIFIED,
                        decisions = s.decisions + paths.associateWith { FileDecision.APPLIED },
                        message = "Applied ${paths.size} file(s). " + RepairText.NOT_VERIFIED,
                    )
                }
                RepairApplyOutcome.Applied(applyId, paths, chosen.flatMap { it.proposalIds })
            }
            is ApplyOutcome.Stale -> {
                changes.discard(applyId)
                post("Nothing was changed: " + r.paths.joinToString() + " changed after the patch was made. Prepare the repair again.")
                RepairApplyOutcome.Stale(r.paths)
            }
            is ApplyOutcome.Invalid -> {
                changes.discard(applyId)
                post(r.reason)
                RepairApplyOutcome.Failed(r.reason)
            }
            is ApplyOutcome.Failed -> {
                changes.discard(applyId)
                post(r.reason)
                RepairApplyOutcome.Failed(r.reason)
            }
            ApplyOutcome.NotFound -> {
                post("The change could not be applied.")
                RepairApplyOutcome.Failed("The change could not be applied.")
            }
        }
    }

    // ---- rollback -------------------------------------------------------------------------------------------------------

    /**
     * Puts the files of the applied repair [applyId] back exactly as they were. Without [force], a file edited after the
     * repair blocks the rollback and nothing is written; with [force] (ask the user first) those files are restored anyway.
     * Uses the shared change history; if that no longer holds the repair, the saved originals are used.
     */
    suspend fun rollback(applyId: String, force: Boolean = false): RepairRollbackOutcome = mutex.withLock {
        _state.update { it.copy(busy = true) }
        try {
            doRollback(applyId, force)
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private suspend fun doRollback(applyId: String, force: Boolean): RepairRollbackOutcome {
        val log = _state.value.log
        val applied = log.filter { it.event == RepairEvent.APPLIED && it.applyId == applyId }
        if (applied.isEmpty()) return RepairRollbackOutcome.NotFound
        if (log.any { it.event == RepairEvent.ROLLED_BACK && it.applyId == applyId }) return RepairRollbackOutcome.AlreadyRolledBack

        // A forced rollback throws away what the user typed after the repair, so keep the current text first.
        if (force) {
            val reason = preserveCurrentText(applyId, applied)
            if (reason != null) {
                post(reason)
                return RepairRollbackOutcome.Failed(reason)
            }
        }

        when (val r = changes.undo(applyId, force)) {
            is UndoOutcome.Undone -> return finishRollback(applyId, applied)
            is UndoOutcome.Conflict -> {
                post("Nothing was changed: " + r.paths.joinToString() + " was edited after the repair. Rolling back would lose those edits.")
                return RepairRollbackOutcome.Conflict(r.paths)
            }
            is UndoOutcome.Failed -> {
                post(r.reason)
                return RepairRollbackOutcome.Failed(r.reason)
            }
            UndoOutcome.NotFound -> Unit
        }

        // The change history no longer holds it (older than its limit, or lost): use the originals saved before the repair.
        val saved = try {
            backups.load(projectId, applyId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (saved == null || applied.any { it.path !in saved }) {
            val reason = "The saved originals of this repair could not be found, so it cannot be rolled back automatically."
            post(reason)
            return RepairRollbackOutcome.Failed(reason)
        }
        val existing = try { files.list().toHashSet() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val reason = "The project files could not be read: " + e.message.orEmpty()
            post(reason)
            return RepairRollbackOutcome.Failed(reason)
        }
        val conflicts = ArrayList<String>()
        for (e in applied) {
            val current: String? = if (e.path in existing) {
                try { files.read(e.path) } catch (ex: CancellationException) { throw ex } catch (ex: Exception) { null }
            } else null
            if (current == null || TextEdits.hash(current) != e.afterHash) conflicts += e.path
        }
        if (conflicts.isNotEmpty() && !force) {
            post("Nothing was changed: " + conflicts.joinToString() + " was edited after the repair. Rolling back would lose those edits.")
            return RepairRollbackOutcome.Conflict(conflicts)
        }
        for (e in applied) {
            try {
                files.write(e.path, saved.getValue(e.path))
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                val reason = "Could not restore ${e.path}: ${ex.message.orEmpty()}. Files restored before it stay restored."
                post(reason)
                return RepairRollbackOutcome.Failed(reason)
            }
        }
        return finishRollback(applyId, applied)
    }

    /** Null = saved (or nothing to save); otherwise the reason it could not be saved, and the rollback must not go ahead. */
    private suspend fun preserveCurrentText(applyId: String, applied: List<RepairLogEntry>): String? {
        val current = LinkedHashMap<String, String>()
        try {
            val existing = files.list().toHashSet()
            for (e in applied) if (e.path in existing) current[e.path] = files.read(e.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return "The current files could not be read to save them first (${e.message.orEmpty().take(100)}). Nothing was changed."
        }
        if (current.isEmpty()) return null
        return try {
            backups.save(projectId, Fingerprint.of("before-rollback", applyId, clockMillis()), current)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "The current files could not be saved before rolling back (${e.message.orEmpty().take(100)}). Nothing was changed."
        }
    }

    private suspend fun finishRollback(applyId: String, applied: List<RepairLogEntry>): RepairRollbackOutcome {
        val now = clockMillis()
        val paths = applied.map { it.path }
        val plan = _state.value.plan
        appendLog(
            applied.map {
                it.copy(
                    id = Fingerprint.of("repair-log", RepairEvent.ROLLED_BACK.name, applyId, it.path, now),
                    atMillis = now, event = RepairEvent.ROLLED_BACK,
                )
            },
        )
        _state.update { s ->
            val decisions = s.decisions.mapValues { (p, d) -> if (p in paths && d == FileDecision.APPLIED) FileDecision.ROLLED_BACK else d }
            val stillApplied = decisions.values.any { it == FileDecision.APPLIED }
            s.copy(
                decisions = decisions,
                phase = if (plan == null || stillApplied) s.phase else phaseFor(s),
                message = "Rolled back ${paths.size} file(s) to their original text.",
            )
        }
        return RepairRollbackOutcome.RolledBack(applyId, paths, applied.flatMap { it.proposalIds })
    }

    // ---- housekeeping -----------------------------------------------------------------------------------------------------

    /** Closes the current plan. The repair record stays. Nothing is written. */
    fun discard() {
        _state.update { it.copy(phase = RepairPhase.NONE, plan = null, decisions = emptyMap(), message = null) }
    }

    fun dismissMessage() { _state.update { it.copy(message = null) } }

    private fun post(text: String) { _state.update { it.copy(message = text) } }

    private fun setDecision(path: String, d: FileDecision) {
        _state.update { it.copy(decisions = it.decisions + (path to d)) }
    }

    private fun phaseFor(s: RepairState): RepairPhase {
        val p = s.plan
        return when {
            p == null -> RepairPhase.NONE
            p.hasPatches -> RepairPhase.AWAITING_APPROVAL
            else -> RepairPhase.NO_PATCH
        }
    }

    private fun entry(event: RepairEvent, plan: RepairPlan, patch: RepairFilePatch, applyId: String?, now: Long): RepairLogEntry =
        RepairLogEntry(
            id = Fingerprint.of("repair-log", event.name, plan.id, applyId, patch.path, now),
            atMillis = now, event = event, planId = plan.id, applyId = applyId, issueId = plan.issueId, issueSummary = plan.issueTitle,
            path = patch.path, origin = patch.origin.name, title = patch.title, added = patch.added, removed = patch.removed,
            beforeHash = patch.change.baseHash, afterHash = TextEdits.hash(patch.change.after), proposalIds = patch.proposalIds,
        )

    /** Newest first. A log that cannot be saved is kept in memory for this session; the files are already safe. */
    private suspend fun appendLog(newEntries: List<RepairLogEntry>) {
        _state.update { it.copy(log = RepairLogs.trim(newEntries.reversed() + it.log)) }
        try {
            logStore.save(projectId, _state.value.log)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not fatal: see above.
        }
    }
}
