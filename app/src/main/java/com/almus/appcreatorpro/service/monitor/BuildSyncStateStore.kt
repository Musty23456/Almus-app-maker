package com.almus.appcreatorpro.service.monitor

import com.almus.appcreatorpro.data.storage.KeyValueStore

/**
 * Small bookkeeping that survives app restarts: when each build was last checked in the background, and which
 * notification stage was last shown for it (so a restart never repeats a notification). Holds no secrets.
 */
class BuildSyncStateStore(private val store: KeyValueStore) {

    fun lastCheckedAt(buildId: String): Long? = store.getString(CHECKED + buildId)?.toLongOrNull()

    fun markChecked(buildId: String, atMillis: Long) = store.putString(CHECKED + buildId, atMillis.toString())

    fun lastStage(buildId: String): String? = store.getString(STAGE + buildId)

    fun setStage(buildId: String, stage: String) = store.putString(STAGE + buildId, stage)

    /** Forgets everything about builds that are no longer in the history. */
    fun forgetAllExcept(keep: Set<String>) {
        for (key in store.keys()) {
            val id = when {
                key.startsWith(CHECKED) -> key.removePrefix(CHECKED)
                key.startsWith(STAGE) -> key.removePrefix(STAGE)
                else -> continue
            }
            if (id !in keep) store.remove(key)
        }
    }

    private companion object {
        const val CHECKED = "checked."
        const val STAGE = "stage."
    }
}
