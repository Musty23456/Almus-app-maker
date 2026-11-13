package com.almus.appcreatorpro.repair

enum class RepairEvent(val label: String) {
    APPLIED("Applied"),
    REJECTED("Rejected"),
    ROLLED_BACK("Rolled back"),
}

/**
 * One line of the repair record. Every applied change, every rejection and every rollback gets one, per file.
 * Hashes (not text) identify the versions; the original TEXT is kept separately by [RepairBackupStore].
 */
data class RepairLogEntry(
    val id: String,
    val atMillis: Long,
    val event: RepairEvent,
    val planId: String,
    /** Set for APPLIED and ROLLED_BACK: the id of the write (also the id in the change history). */
    val applyId: String?,
    val issueId: String,
    val issueSummary: String,
    val path: String,
    /** `FixOrigin.name` of the patch (RULE or AI). */
    val origin: String,
    val title: String,
    val added: Int,
    val removed: Int,
    val beforeHash: String?,
    val afterHash: String?,
    val proposalIds: List<String> = emptyList(),
)

/** The repair record of each project. Newest first. */
interface RepairLogStore {
    suspend fun load(projectId: String): List<RepairLogEntry>
    suspend fun save(projectId: String, entries: List<RepairLogEntry>)
}

class InMemoryRepairLogStore : RepairLogStore {
    private val map = HashMap<String, List<RepairLogEntry>>()
    override suspend fun load(projectId: String): List<RepairLogEntry> = map[projectId].orEmpty()
    override suspend fun save(projectId: String, entries: List<RepairLogEntry>) { map[projectId] = entries }
}

/**
 * The ORIGINAL text of every file a repair is about to change, saved before anything is written. It is separate from
 * the change history (which is capped and could be lost), so the originals stay available for a rollback.
 */
interface RepairBackupStore {
    suspend fun save(projectId: String, applyId: String, files: Map<String, String>)

    /** Path to original text, or null when there is no backup with that id. */
    suspend fun load(projectId: String, applyId: String): Map<String, String>?
}

class InMemoryRepairBackupStore : RepairBackupStore {
    val saved = LinkedHashMap<String, Map<String, String>>()
    var failSaves: Boolean = false
    override suspend fun save(projectId: String, applyId: String, files: Map<String, String>) {
        if (failSaves) throw java.io.IOException("disk full")
        saved["$projectId|$applyId"] = LinkedHashMap(files)
    }
    override suspend fun load(projectId: String, applyId: String): Map<String, String>? = saved["$projectId|$applyId"]
}

/** One applied repair as the screen lists it, with what became of it. */
data class AppliedRepairView(
    val applyId: String,
    val atMillis: Long,
    val issueSummary: String,
    val paths: List<String>,
    val rolledBack: Boolean,
)

object RepairLogs {
    const val MAX_ENTRIES = 500

    /** Applied repairs, newest first. A repair is "rolled back" once a ROLLED_BACK entry carries its apply id. */
    fun appliedRepairs(log: List<RepairLogEntry>): List<AppliedRepairView> {
        val rolledBack = log.filter { it.event == RepairEvent.ROLLED_BACK }.mapNotNull { it.applyId }.toSet()
        val applied = log.filter { it.event == RepairEvent.APPLIED && it.applyId != null }
        val out = ArrayList<AppliedRepairView>()
        for ((applyId, group) in applied.groupBy { it.applyId!! }) {
            val first = group.minByOrNull { it.atMillis } ?: continue
            out += AppliedRepairView(applyId, first.atMillis, first.issueSummary, group.map { it.path }.distinct(), applyId in rolledBack)
        }
        return out.sortedByDescending { it.atMillis }
    }

    fun trim(entries: List<RepairLogEntry>): List<RepairLogEntry> = entries.take(MAX_ENTRIES)
}
