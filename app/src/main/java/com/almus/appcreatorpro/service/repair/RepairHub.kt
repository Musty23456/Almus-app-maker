package com.almus.appcreatorpro.service.repair

import com.almus.appcreatorpro.repair.RepairBackupStore
import com.almus.appcreatorpro.repair.RepairLogStore
import com.almus.appcreatorpro.repair.RepairSession
import com.almus.appcreatorpro.service.ai.AiHub
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps one [RepairSession] per project, so the plan, the user's decisions and the repair record survive leaving the
 * screen. It shares the project's AI change manager (the same staleness checks, change history and undo as the AI
 * assistant and the agent) and its file access. Held by the app container; lost when the app process ends, while the
 * repair record, the saved originals and the change history are on disk.
 */
class RepairHub(
    private val ai: AiHub,
    private val logStore: RepairLogStore,
    private val backups: RepairBackupStore,
) {
    private val lock = Mutex()
    private val sessions = LinkedHashMap<String, RepairSession>()

    suspend fun session(projectId: String): RepairSession = lock.withLock {
        sessions[projectId]?.let { return@withLock it }
        while (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
        val project = ai.session(projectId)
        // The change history must be loaded before anything is applied, or saving it would overwrite older records.
        project.init()
        val s = RepairSession(projectId, project.files, project.changes, logStore, backups)
        s.load()
        sessions[projectId] = s
        s
    }

    private companion object { const val MAX_SESSIONS = 4 }
}
