package com.almus.appcreatorpro.service.ai

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiProjectGenerator
import com.almus.appcreatorpro.ai.AiProjectSession
import com.almus.appcreatorpro.ai.ChangeHistoryStore
import com.almus.appcreatorpro.ai.ChatHistoryStore
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps one [AiProjectSession] per project, so chat, pending changes and the undo history survive leaving the screen.
 * Held by the app container; the in-memory part is lost when the app process ends (chat and change history are on disk).
 */
class AiHub(
    val backend: AiBackend,
    private val sourceFiles: SourceFileRepository,
    private val projects: ProjectRepository,
    private val chatHistory: ChatHistoryStore,
    private val changeHistory: ChangeHistoryStore,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val sessions = LinkedHashMap<String, AiProjectSession>()

    suspend fun session(projectId: String): AiProjectSession = lock.withLock {
        sessions[projectId]?.let { return@withLock it }
        while (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
        val project = try { projects.getProject(projectId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        val s = AiProjectSession(
            projectId = projectId, projectName = project?.name, projectType = project?.type, backend = backend,
            files = RepositoryAiProjectFiles(projectId, sourceFiles), chatHistory = chatHistory, changeHistory = changeHistory, scope = scope,
        )
        sessions[projectId] = s
        s
    }

    /** One generator for the app: creating a project from a description is not tied to an existing project. */
    val generator: AiProjectGenerator = AiProjectGenerator(
        backend = backend, projects = projects, filesFor = { id -> RepositoryAiProjectFiles(id, sourceFiles) },
        changeHistory = changeHistory, scope = scope,
    )

    private companion object { const val MAX_SESSIONS = 4 }
}
