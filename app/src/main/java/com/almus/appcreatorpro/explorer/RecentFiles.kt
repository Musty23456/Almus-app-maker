package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.data.storage.KeyValueStore

/** The files opened most recently in one project, newest first. Immutable: every change returns a new list. */
data class RecentFiles(val paths: List<String> = emptyList()) {

    fun touched(path: String): RecentFiles =
        RecentFiles((listOf(path) + paths.filterNot { it == path }).take(MAX))

    fun removed(path: String): RecentFiles = RecentFiles(paths.filterNot { it == path })

    fun removedUnder(folder: String): RecentFiles =
        RecentFiles(paths.filterNot { PathOps.isInside(folder, it) })

    fun renamed(from: String, to: String): RecentFiles =
        RecentFiles(paths.map { if (it == from) to else it }.distinct())

    fun renamedUnder(fromFolder: String, toFolder: String): RecentFiles =
        RecentFiles(paths.map { PathOps.moveUnder(fromFolder, toFolder, it) ?: it }.distinct())

    /** Drops paths that no longer exist. */
    fun pruned(existing: Set<String>): RecentFiles = RecentFiles(paths.filter { it in existing })

    companion object {
        const val MAX = 20
    }
}

/** Where the recent list survives a restart. */
interface RecentFilesStore {
    fun load(projectId: String): RecentFiles
    fun save(projectId: String, recent: RecentFiles)
}

class InMemoryRecentFilesStore : RecentFilesStore {
    private val map = HashMap<String, RecentFiles>()
    override fun load(projectId: String): RecentFiles = map[projectId] ?: RecentFiles()
    override fun save(projectId: String, recent: RecentFiles) { map[projectId] = recent }
}

/** One key per project: paths joined by newlines (a valid project path never contains a newline). */
class KeyValueRecentFilesStore(private val store: KeyValueStore) : RecentFilesStore {
    override fun load(projectId: String): RecentFiles {
        val raw = store.getString(KEY_PREFIX + projectId) ?: return RecentFiles()
        return RecentFiles(raw.split('\n').filter { it.isNotBlank() }.distinct().take(RecentFiles.MAX))
    }

    override fun save(projectId: String, recent: RecentFiles) {
        if (recent.paths.isEmpty()) store.remove(KEY_PREFIX + projectId)
        else store.putString(KEY_PREFIX + projectId, recent.paths.joinToString("\n"))
    }

    private companion object {
        const val KEY_PREFIX = "recent.files."
    }
}
