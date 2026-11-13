package com.almus.appcreatorpro.service.artifacts

enum class ArtifactSort(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LARGEST("Largest first"),
    NAME("Name"),
}

data class ArtifactFilter(
    val type: ArtifactType? = null,
    val kind: BuildKind? = null,
    val query: String = "",
) {
    val isActive: Boolean get() = type != null || kind != null || query.isNotBlank()
}

/** The saved files of one project, in the order the user chose. */
data class ArtifactGroup(val projectName: String, val items: List<ArtifactEntry>) {
    val totalBytes: Long get() = items.sumOf { it.sizeBytes }
}

/** What "Clean up older files" would do. Nothing happens until the user confirms. */
data class CleanupPlan(val delete: List<ArtifactEntry>, val keep: List<ArtifactEntry>) {
    val bytes: Long get() = delete.sumOf { it.sizeBytes }
}

/** Grouping, filtering, sorting and clean-up planning of saved artifacts. Pure functions: easy to test. */
object ArtifactOrganizer {

    fun arrange(items: List<ArtifactEntry>, filter: ArtifactFilter, sort: ArtifactSort): List<ArtifactGroup> {
        val query = filter.query.trim().lowercase()
        val visible = items.filter { e ->
            (filter.type == null || e.type == filter.type) &&
                (filter.kind == null || e.kindHint == filter.kind) &&
                (query.isEmpty() || matches(e, query))
        }
        val comparator: Comparator<ArtifactEntry> = when (sort) {
            ArtifactSort.NEWEST -> compareByDescending<ArtifactEntry> { it.savedAtMillis }.thenBy { it.fileName }
            ArtifactSort.OLDEST -> compareBy<ArtifactEntry> { it.savedAtMillis }.thenBy { it.fileName }
            ArtifactSort.LARGEST -> compareByDescending<ArtifactEntry> { it.sizeBytes }.thenBy { it.fileName }
            ArtifactSort.NAME -> compareBy<ArtifactEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.fileName }.thenBy { it.key }
        }
        val groups = visible.groupBy { it.projectName }.map { (name, list) -> ArtifactGroup(name, list.sortedWith(comparator)) }
        return when (sort) {
            ArtifactSort.NEWEST -> groups.sortedByDescending { g -> g.items.maxOf { it.savedAtMillis } }
            ArtifactSort.OLDEST -> groups.sortedBy { g -> g.items.minOf { it.savedAtMillis } }
            ArtifactSort.LARGEST -> groups.sortedByDescending { it.totalBytes }
            ArtifactSort.NAME -> groups.sortedBy { it.projectName.lowercase() }
        }
    }

    private fun matches(e: ArtifactEntry, query: String): Boolean {
        val r = e.report
        return e.fileName.lowercase().contains(query) ||
            e.projectName.lowercase().contains(query) ||
            (r?.packageName?.lowercase()?.contains(query) == true) ||
            (r?.versionName?.lowercase()?.contains(query) == true)
    }

    /**
     * Keeps the newest [keepPerGroup] files of every (project, type, debug/release) group and proposes deleting the
     * rest. [keepPerGroup] is never below 1, so the newest file of every group always stays: the newest release build
     * of a project can never be proposed for deletion.
     */
    fun cleanupPlan(items: List<ArtifactEntry>, keepPerGroup: Int): CleanupPlan {
        val keepCount = keepPerGroup.coerceAtLeast(1)
        val delete = ArrayList<ArtifactEntry>()
        val keep = ArrayList<ArtifactEntry>()
        val groups = items.groupBy { Triple(it.projectName, it.type, it.kindHint) }
        for ((_, list) in groups) {
            val ordered = list.sortedWith(compareByDescending<ArtifactEntry> { it.savedAtMillis }.thenBy { it.key })
            keep += ordered.take(keepCount)
            delete += ordered.drop(keepCount)
        }
        return CleanupPlan(
            delete = delete.sortedByDescending { it.sizeBytes },
            keep = keep,
        )
    }
}
