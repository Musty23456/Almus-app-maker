package com.almus.appcreatorpro.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reads and writes project files by project-relative path. The real implementation wraps `SourceFileRepository`. */
interface ProjectFileAccess {
    suspend fun read(path: String): String
    suspend fun write(path: String, content: String)
}

enum class FixStatus { PENDING, APPLIED, REJECTED }

data class FixEntry(val proposal: FixProposal, val status: FixStatus)

sealed interface FixResult {
    data class Applied(val entry: FixEntry) : FixResult

    /** The file changed after the suggestion was made. Nothing was written; run the check again. */
    data class Stale(val path: String) : FixResult
    data class Invalid(val reason: String) : FixResult
    data class Failed(val reason: String) : FixResult
    data object NotFound : FixResult
}

sealed interface UndoResult {
    data class Undone(val proposal: FixProposal) : UndoResult

    /** The file was edited after the fix was applied; undoing would throw that work away, so nothing was written. */
    data class Conflict(val path: String) : UndoResult
    data class Failed(val reason: String) : UndoResult
    data object NothingToUndo : UndoResult
}

private data class AppliedChange(val proposal: FixProposal, val before: String, val after: String)

/**
 * Keeps the list of suggested fixes and the undo history.
 *  - preview: shows before/after without touching the file;
 *  - apply: writes the change, only if the file is exactly what the suggestion was written for;
 *  - reject: hides a suggestion (it stays rejected when the same suggestion is offered again);
 *  - undo: restores the text from before the most recent applied fix, unless the file was edited since.
 * The undo history lives in memory for the current app session.
 */
class FixManager(private val files: ProjectFileAccess) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<FixEntry>>(emptyList())
    val entries: StateFlow<List<FixEntry>> = _entries.asStateFlow()

    private val history = ArrayList<AppliedChange>()
    private val _undoDepth = MutableStateFlow(0)

    /** Number of fixes that can be undone. */
    val undoDepth: StateFlow<Int> = _undoDepth.asStateFlow()

    /**
     * Replaces the pending suggestions with [proposals]. Applied and rejected ones are remembered; a rejected
     * suggestion offered again stays rejected.
     */
    fun offer(proposals: List<FixProposal>) {
        _entries.update { current ->
            val keep = current.filter { it.status != FixStatus.PENDING }
            val known = keep.map { it.proposal.id }.toSet()
            val fresh = proposals.distinctBy { it.id }.filter { it.id !in known }.map { FixEntry(it, FixStatus.PENDING) }
            keep + fresh
        }
    }

    fun entry(id: String): FixEntry? = _entries.value.firstOrNull { it.proposal.id == id }

    suspend fun preview(id: String): FixPreview? {
        val e = entry(id) ?: return null
        val text = try { files.read(e.proposal.path) } catch (ex: Exception) { return null }
        if (TextEdits.hash(text) != e.proposal.baseHash) return null
        return FixPreviewer.preview(text, e.proposal)
    }

    suspend fun apply(id: String): FixResult = mutex.withLock {
        val e = entry(id) ?: return@withLock FixResult.NotFound
        if (e.status == FixStatus.APPLIED) return@withLock FixResult.Invalid("This fix is already applied.")
        val p = e.proposal
        val before = try { files.read(p.path) } catch (ex: Exception) { return@withLock FixResult.Failed("Could not read ${p.path}: ${ex.message}") }
        if (TextEdits.hash(before) != p.baseHash) return@withLock FixResult.Stale(p.path)
        val after = when (val r = TextEdits.apply(before, p.edits)) {
            is TextEdits.Result.Ok -> r.text
            is TextEdits.Result.Invalid -> return@withLock FixResult.Invalid(r.reason)
        }
        try { files.write(p.path, after) } catch (ex: Exception) { return@withLock FixResult.Failed("Could not save ${p.path}: ${ex.message}") }
        history += AppliedChange(p, before, after)
        _undoDepth.value = history.size
        val updated = FixEntry(p, FixStatus.APPLIED)
        _entries.update { list -> list.map { if (it.proposal.id == id) updated else it } }
        FixResult.Applied(updated)
    }

    fun reject(id: String): Boolean {
        val e = entry(id) ?: return false
        if (e.status == FixStatus.APPLIED) return false
        _entries.update { list -> list.map { if (it.proposal.id == id) it.copy(status = FixStatus.REJECTED) else it } }
        return true
    }

    /** Brings a rejected suggestion back into the pending list. */
    fun restore(id: String): Boolean {
        val e = entry(id) ?: return false
        if (e.status != FixStatus.REJECTED) return false
        _entries.update { list -> list.map { if (it.proposal.id == id) it.copy(status = FixStatus.PENDING) else it } }
        return true
    }

    suspend fun undo(): UndoResult = mutex.withLock {
        val last = history.lastOrNull() ?: return@withLock UndoResult.NothingToUndo
        val current = try { files.read(last.proposal.path) } catch (ex: Exception) { return@withLock UndoResult.Failed("Could not read ${last.proposal.path}: ${ex.message}") }
        if (current != last.after) return@withLock UndoResult.Conflict(last.proposal.path)
        try { files.write(last.proposal.path, last.before) } catch (ex: Exception) { return@withLock UndoResult.Failed("Could not save ${last.proposal.path}: ${ex.message}") }
        history.removeAt(history.size - 1)
        _undoDepth.value = history.size
        _entries.update { list -> list.map { if (it.proposal.id == last.proposal.id) it.copy(status = FixStatus.PENDING) else it } }
        UndoResult.Undone(last.proposal)
    }

    /**
     * Phase 23: the repair engine wrote the files of these suggestions (and keeps its own record and rollback), so they
     * are shown as applied here too. They are not added to this list's undo history.
     */
    fun markApplied(ids: Set<String>) {
        if (ids.isEmpty()) return
        _entries.update { list -> list.map { if (it.proposal.id in ids && it.status == FixStatus.PENDING) it.copy(status = FixStatus.APPLIED) else it } }
    }

    /** Phase 23: the repair was rolled back, so the suggestions can be looked at again. */
    fun markPending(ids: Set<String>) {
        if (ids.isEmpty()) return
        _entries.update { list -> list.map { if (it.proposal.id in ids && it.status == FixStatus.APPLIED) it.copy(status = FixStatus.PENDING) else it } }
    }

    /** The fix that [undo] would revert next. */
    fun nextUndo(): FixProposal? = history.lastOrNull()?.proposal

    fun clear() {
        history.clear()
        _undoDepth.value = 0
        _entries.value = emptyList()
    }
}
