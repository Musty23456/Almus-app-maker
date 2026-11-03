package com.almus.appcreatorpro.service.debug

import com.almus.appcreatorpro.debug.AiDebugProvider
import com.almus.appcreatorpro.debug.DebugSession
import com.almus.appcreatorpro.debug.NoAiProvider
import com.almus.appcreatorpro.debug.ProjectFileAccess
import com.almus.appcreatorpro.debug.RawBuildLog
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.service.build.BuildDetailsService

/** [ProjectFileAccess] for one project, on top of the app's source-file storage. */
class RepositoryFileAccess(private val projectId: String, private val repo: SourceFileRepository) : ProjectFileAccess {
    override suspend fun read(path: String): String = repo.readFile(projectId, path)
    override suspend fun write(path: String, content: String) = repo.writeFile(projectId, path, content)
}

/**
 * Keeps one [DebugSession] per (project, build) so the results, the fix list and the undo history survive leaving the
 * screen and coming back (for example to the editor and back). Held by the app container; lost when the app process ends.
 */
class DebugHub(
    private val sourceFiles: SourceFileRepository,
    private val projects: ProjectRepository,
    private val details: BuildDetailsService,
    /** Phase 8: no AI is connected. A later phase passes its Gemini provider here. */
    private val ai: AiDebugProvider = NoAiProvider,
) {
    private val sessions = LinkedHashMap<String, DebugSession>()

    /** Only text-like project files are checked; build output and very large files are skipped. */
    private val checkedExtensions = setOf("kt", "kts", "java", "xml", "gradle", "properties", "pro", "toml", "json")

    @Synchronized
    fun session(projectId: String, buildId: String?, projectName: String?): DebugSession {
        val key = "$projectId|${buildId.orEmpty()}"
        return sessions.getOrPut(key) {
            // Keep memory bounded: only the few most recent sessions are kept.
            while (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())
            val access = RepositoryFileAccess(projectId, sourceFiles)
            DebugSession(
                projectName = projectName,
                buildId = buildId,
                files = access,
                listPaths = { listCheckable(projectId) },
                loadLog = { if (buildId == null) RawBuildLog(null, null) else details.loadRawLog(buildId) },
                ai = ai,
            )
        }
    }

    suspend fun projectName(projectId: String): String? = try { projects.getProject(projectId)?.name } catch (e: Exception) { null }

    private suspend fun listCheckable(projectId: String): List<String> =
        sourceFiles.listFiles(projectId)
            .filter { it.sizeBytes <= SourceFileRepository.MAX_FILE_BYTES }
            .map { it.path }
            .filter { p ->
                p.substringAfterLast('.', "").lowercase() in checkedExtensions &&
                    p.split('/').none { it == "build" || it == ".gradle" || it == ".git" || it == ".idea" }
            }
            .sorted()

    private companion object {
        const val MAX_SESSIONS = 4
    }
}
