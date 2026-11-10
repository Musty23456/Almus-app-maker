package com.almus.appcreatorpro.service.ai

import com.almus.appcreatorpro.agent.AgentProjectLauncher
import com.almus.appcreatorpro.agent.AgentRunStore
import com.almus.appcreatorpro.agent.AgentRunner
import com.almus.appcreatorpro.agent.InMemoryAgentRunStore
import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiProviderRegistry
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
    /** Phase 13: where the agent's runs are kept, and which AI provider it uses (today only Gemini). */
    private val agentStore: AgentRunStore = InMemoryAgentRunStore(),
    val providers: AiProviderRegistry = AiProviderRegistry.single(backend),
) {
    private val lock = Mutex()
    private val sessions = LinkedHashMap<String, AiProjectSession>()
    private val agentLock = Mutex()
    private val agents = LinkedHashMap<String, AgentRunner>()

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

    /**
     * The development agent of a project. It shares the project session's change manager, so what the agent applies shows
     * up in the same history and can be undone from either place. A runner that is working is never evicted.
     */
    suspend fun agent(projectId: String): AgentRunner {
        val session = session(projectId)
        // The history must be loaded before the agent applies anything, or saving would overwrite older records.
        session.init()
        val runner = agentLock.withLock {
            agents[projectId]?.let { return@withLock it }
            val idle = agents.entries.filter { !it.value.state.value.busy }
            if (agents.size >= MAX_SESSIONS && idle.isNotEmpty()) agents.remove(idle.first().key)
            val project = try { projects.getProject(projectId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            val r = AgentRunner(
                projectId = projectId, projectName = project?.name, projectType = project?.type, backend = providers.default().backend,
                base = session.files, changes = session.changes, store = agentStore, scope = scope,
            )
            agents[projectId] = r
            r
        }
        runner.load()
        return runner
    }

    /** Natural language project creation by the agent: plan, approval, then the project is created and the agent starts in it. */
    fun newAgentLauncher(): AgentProjectLauncher = AgentProjectLauncher(
        backend = providers.default().backend, projects = projects, scope = scope,
        startAgent = { id, goal, plan -> agent(id).startWithPlan(goal, plan) },
    )

    /** One generator for the app: creating a project from a description is not tied to an existing project. */
    val generator: AiProjectGenerator = AiProjectGenerator(
        backend = backend, projects = projects, filesFor = { id -> RepositoryAiProjectFiles(id, sourceFiles) },
        changeHistory = changeHistory, scope = scope,
    )

    private companion object { const val MAX_SESSIONS = 4 }
}
