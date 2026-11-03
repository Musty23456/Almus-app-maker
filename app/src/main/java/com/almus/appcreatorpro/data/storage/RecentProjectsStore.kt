package com.almus.appcreatorpro.data.storage

import com.almus.appcreatorpro.domain.repository.RecentProjectsTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps "project id -> last opened" in a key-value store (private preferences), plus an observable copy. */
class RecentProjectsStore(
    private val store: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentProjectsTracker {

    private val state = MutableStateFlow(load())
    override val opened: StateFlow<Map<String, Long>> = state.asStateFlow()

    override fun markOpened(projectId: String) {
        if (projectId.isBlank()) return
        val now = clock()
        store.putString(KEY_PREFIX + projectId, now.toString())
        state.value = state.value + (projectId to now)
    }

    override fun forget(projectId: String) {
        store.remove(KEY_PREFIX + projectId)
        state.value = state.value - projectId
    }

    private fun load(): Map<String, Long> {
        val result = LinkedHashMap<String, Long>()
        for (key in store.keys()) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val millis = store.getString(key)?.toLongOrNull() ?: continue
            result[key.removePrefix(KEY_PREFIX)] = millis
        }
        return result
    }

    private companion object {
        const val KEY_PREFIX = "opened."
    }
}
