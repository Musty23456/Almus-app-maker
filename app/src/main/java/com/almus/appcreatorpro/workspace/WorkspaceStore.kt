package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.data.storage.KeyValueStore
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.bool
import com.almus.appcreatorpro.service.github.long
import com.almus.appcreatorpro.service.github.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The workspaces on this phone, in private preferences. The list is ordered by "last opened", which is also the
 * "recent repository history". Contains no tokens (see [RepoWorkspace]).
 */
class WorkspaceStore(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val state = MutableStateFlow(load())

    /** Most recently opened first. */
    val workspaces: StateFlow<List<RepoWorkspace>> = state.asStateFlow()

    fun get(id: String): RepoWorkspace? = state.value.firstOrNull { it.id == id }

    fun find(owner: String, repoName: String, branch: String): RepoWorkspace? = state.value.firstOrNull {
        it.owner.equals(owner, ignoreCase = true) && it.repoName.equals(repoName, ignoreCase = true) && it.branch == branch
    }

    fun findByProject(projectId: String): RepoWorkspace? = state.value.firstOrNull { it.projectId == projectId }

    /** The newest [limit] workspaces. */
    fun recent(limit: Int = 5): List<RepoWorkspace> = state.value.take(limit)

    fun save(workspace: RepoWorkspace) {
        store.putString(KEY_PREFIX + workspace.id, encode(workspace))
        state.value = (state.value.filter { it.id != workspace.id } + workspace).sortedByDescending { it.lastOpenedAtMillis }
    }

    fun markOpened(id: String) {
        val current = get(id) ?: return
        save(current.copy(lastOpenedAtMillis = clock()))
        store.putString(ACTIVE_KEY, id)
    }

    fun activeId(): String? = store.getString(ACTIVE_KEY)?.takeIf { id -> get(id) != null }

    fun setActive(id: String?) {
        if (id == null) store.remove(ACTIVE_KEY) else store.putString(ACTIVE_KEY, id)
    }

    /** Forgets the workspace record only. The local project and its files are NOT touched. */
    fun remove(id: String) {
        store.remove(KEY_PREFIX + id)
        if (store.getString(ACTIVE_KEY) == id) store.remove(ACTIVE_KEY)
        state.value = state.value.filter { it.id != id }
    }

    private fun load(): List<RepoWorkspace> {
        val out = ArrayList<RepoWorkspace>()
        for (key in store.keys()) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val text = store.getString(key) ?: continue
            decode(text)?.let { out += it }
        }
        return out.sortedByDescending { it.lastOpenedAtMillis }
    }

    internal companion object {
        const val KEY_PREFIX = "workspace."
        const val ACTIVE_KEY = "active"

        fun encode(w: RepoWorkspace): String = MiniJson.stringify(
            mapOf(
                "id" to w.id, "owner" to w.owner, "repo" to w.repoName, "branch" to w.branch,
                "sha" to w.commitSha, "title" to w.commitTitle, "private" to w.isPrivate,
                "defaultBranch" to w.defaultBranch, "description" to w.description, "url" to w.htmlUrl,
                "project" to w.projectId, "imported" to w.importedAtMillis, "opened" to w.lastOpenedAtMillis,
                "synced" to w.lastSyncedAtMillis,
            ),
        )

        fun decode(text: String): RepoWorkspace? {
            return try {
            val o = MiniJson.parse(text).asObj()
            if (o == null) null else RepoWorkspace(
                id = o.str("id") ?: return null,
                owner = o.str("owner") ?: return null,
                repoName = o.str("repo") ?: return null,
                branch = o.str("branch") ?: return null,
                commitSha = o.str("sha").orEmpty(),
                commitTitle = o.str("title").orEmpty(),
                isPrivate = o.bool("private") ?: false,
                defaultBranch = o.str("defaultBranch") ?: "main",
                description = o.str("description"),
                htmlUrl = o.str("url").orEmpty(),
                projectId = o.str("project") ?: return null,
                importedAtMillis = o.long("imported") ?: 0L,
                lastOpenedAtMillis = o.long("opened") ?: 0L,
                lastSyncedAtMillis = o.long("synced") ?: 0L,
            )
        } catch (e: MiniJson.JsonException) {
            null
        }
        }
    }
}
