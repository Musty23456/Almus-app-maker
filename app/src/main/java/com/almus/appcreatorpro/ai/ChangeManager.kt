package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.TextEdits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ChangeStatus { PENDING, APPLIED, REJECTED }

data class ChangeEntry(val changeSet: ChangeSet, val status: ChangeStatus)

/** What one applied change set did to the files; enough to put every file back exactly as it was. */
data class AppliedFile(val path: String, val before: String?, val after: String)

data class AppliedRecord(val id: String, val title: String, val appliedAtMillis: Long, val files: List<AppliedFile>, val undone: Boolean = false)

/** Applied changes survive leaving the screen and restarting the app, so "restore" keeps working. */
interface ChangeHistoryStore {
    /** Newest first. */
    suspend fun load(projectId: String): List<AppliedRecord>
    suspend fun save(projectId: String, records: List<AppliedRecord>)
}

class InMemoryChangeHistoryStore : ChangeHistoryStore {
    private val map = HashMap<String, List<AppliedRecord>>()
    override suspend fun load(projectId: String): List<AppliedRecord> = map[projectId].orEmpty()
    override suspend fun save(projectId: String, records: List<AppliedRecord>) { map[projectId] = records }
}

sealed interface ApplyOutcome {
    data class Applied(val record: AppliedRecord) : ApplyOutcome

    /** Files changed (or appeared) after the suggestion was made. NOTHING was written. */
    data class Stale(val paths: List<String>) : ApplyOutcome
    data class Invalid(val reason: String) : ApplyOutcome
    data class Failed(val reason: String) : ApplyOutcome
    data object NotFound : ApplyOutcome
}

sealed interface UndoOutcome {
    data class Undone(val record: AppliedRecord) : UndoOutcome

    /** Files were edited after the change; undoing would throw that work away. NOTHING was written. */
    data class Conflict(val paths: List<String>) : UndoOutcome
    data class Failed(val reason: String) : UndoOutcome
    data object NotFound : UndoOutcome
}

/**
 * Nothing the AI proposes touches a project file until the user applies it here.
 *  - apply: checks every selected file first (stale = refuse), writes them, rolls back if one write fails;
 *  - reject / bring back a rejected proposal;
 *  - undo: restores the files of one applied change, unless they were edited since (then it asks, see [undo] `force`).
 */
class AiChangeManager(
    private val projectId: String,
    private val files: AiProjectFiles,
    private val store: ChangeHistoryStore,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private val _entries = MutableStateFlow<List<ChangeEntry>>(emptyList())
    val entries: StateFlow<List<ChangeEntry>> = _entries.asStateFlow()
    private val _history = MutableStateFlow<List<AppliedRecord>>(emptyList())
    val history: StateFlow<List<AppliedRecord>> = _history.asStateFlow()

    suspend fun load() { _history.value = store.load(projectId) }

    fun offer(set: ChangeSet) {
        _entries.update { list -> list.filter { it.changeSet.id != set.id } + ChangeEntry(set, ChangeStatus.PENDING) }
    }

    fun entry(id: String): ChangeEntry? = _entries.value.firstOrNull { it.changeSet.id == id }

    fun reject(id: String): Boolean {
        val e = entry(id) ?: return false
        if (e.status != ChangeStatus.PENDING) return false
        setStatus(id, ChangeStatus.REJECTED)
        return true
    }

    fun bringBack(id: String): Boolean {
        val e = entry(id) ?: return false
        if (e.status != ChangeStatus.REJECTED) return false
        setStatus(id, ChangeStatus.PENDING)
        return true
    }

    fun discard(id: String) { _entries.update { list -> list.filter { it.changeSet.id != id } } }

    private fun setStatus(id: String, status: ChangeStatus) {
        _entries.update { list -> list.map { if (it.changeSet.id == id) it.copy(status = status) else it } }
    }

    /** [selected] = the files to apply (null = all of them). */
    suspend fun apply(id: String, selected: Set<String>? = null): ApplyOutcome = mutex.withLock {
        val e = entry(id) ?: return@withLock ApplyOutcome.NotFound
        if (e.status == ChangeStatus.APPLIED) return@withLock ApplyOutcome.Invalid("This change is already applied.")
        val chosen = e.changeSet.changes.filter { selected == null || it.path in selected }
        if (chosen.isEmpty()) return@withLock ApplyOutcome.Invalid("Select at least one file to apply.")

        // 1) Check everything before writing anything.
        val existing = try { files.list().toHashSet() } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) {
            return@withLock ApplyOutcome.Failed("The project files could not be read: ${ex.message.orEmpty()}")
        }
        val stale = ArrayList<String>()
        for (c in chosen) {
            when (c.kind) {
                ChangeKind.CREATE -> if (c.path in existing) stale += c.path
                ChangeKind.MODIFY -> {
                    if (c.path !in existing) { stale += c.path; continue }
                    val current = try { files.read(c.path) } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) { stale += c.path; continue }
                    if (TextEdits.hash(current) != c.baseHash) stale += c.path
                }
            }
        }
        if (stale.isNotEmpty()) return@withLock ApplyOutcome.Stale(stale)

        // 2) Write; if one write fails, put back the ones already written.
        val written = ArrayList<ProposedChange>()
        for (c in chosen) {
            try {
                files.write(c.path, c.after)
                written += c
            } catch (ex: kotlinx.coroutines.CancellationException) {
                rollback(written)
                throw ex
            } catch (ex: Exception) {
                val restored = rollback(written)
                return@withLock ApplyOutcome.Failed("Could not save ${c.path}: ${ex.message.orEmpty()}" + if (restored) " Earlier files were put back." else " Some files could not be put back; check the project.")
            }
        }
        val record = AppliedRecord(e.changeSet.id, e.changeSet.title, clockMillis(), chosen.map { AppliedFile(it.path, it.before, it.after) })
        _history.update { (listOf(record) + it.filter { r -> r.id != record.id }).take(MAX_HISTORY) }
        persist()
        setStatus(id, ChangeStatus.APPLIED)
        ApplyOutcome.Applied(record)
    }

    private suspend fun rollback(written: List<ProposedChange>): Boolean {
        var ok = true
        for (c in written.asReversed()) {
            try {
                if (c.before == null) files.delete(c.path) else files.write(c.path, c.before)
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) { ok = false }
        }
        return ok
    }

    /**
     * Puts the files of the applied change [recordId] back. Without [force], a file edited after the change blocks the
     * undo (nothing is written). With [force] those files are restored anyway - the caller must have asked the user.
     */
    suspend fun undo(recordId: String, force: Boolean = false): UndoOutcome = mutex.withLock {
        val record = _history.value.firstOrNull { it.id == recordId && !it.undone } ?: return@withLock UndoOutcome.NotFound
        val conflicts = ArrayList<String>()
        val exists = try { files.list().toHashSet() } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) {
            return@withLock UndoOutcome.Failed("The project files could not be read: ${ex.message.orEmpty()}")
        }
        for (f in record.files) {
            val current = if (f.path in exists) try { files.read(f.path) } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) { null } else null
            if (current != f.after) conflicts += f.path
        }
        if (conflicts.isNotEmpty() && !force) return@withLock UndoOutcome.Conflict(conflicts)
        for (f in record.files.asReversed()) {
            try {
                if (f.before == null) { if (f.path in exists) files.delete(f.path) } else files.write(f.path, f.before)
            } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) {
                return@withLock UndoOutcome.Failed("Could not restore ${f.path}: ${ex.message.orEmpty()}")
            }
        }
        val done = record.copy(undone = true)
        _history.update { list -> list.map { if (it.id == recordId) done else it } }
        persist()
        // The proposal can be looked at (and applied) again.
        if (entry(recordId)?.status == ChangeStatus.APPLIED) setStatus(recordId, ChangeStatus.PENDING)
        UndoOutcome.Undone(done)
    }

    private suspend fun persist() {
        try { store.save(projectId, _history.value) } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) { /* history is a convenience; the files are already safe */ }
    }

    companion object { const val MAX_HISTORY = 30 }
}
