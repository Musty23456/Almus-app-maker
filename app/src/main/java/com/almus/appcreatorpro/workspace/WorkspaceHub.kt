package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ChatHistoryStore
import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.ContextSuggester
import com.almus.appcreatorpro.ai.ProjectContextBuilder
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.service.ai.RepositoryAiProjectFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Builds what the AI may see about a workspace's project. Reads only; every file goes through [ProjectContextBuilder],
 * which skips credential files and build output and redacts secrets in file text before anything can be sent.
 */
class WorkspaceContextProvider(
    private val workspaceId: String,
    private val store: WorkspaceStore,
    private val files: AiProjectFiles,
    private val projects: ProjectRepository,
) {
    private var digest: WorkspaceDigest? = null

    /** Forget the cached digest (call after the files changed, for example after a sync). */
    fun invalidate() { digest = null }

    suspend fun build(userText: String, stage: WorkspaceStage): WorkspacePromptContext {
        val workspace = store.get(workspaceId)
            ?: return WorkspacePromptContext("You are an assistant for a project that is no longer on this phone. Say so and stop.", null)
        val instructions = WorkspacePrompts.instructions(workspace, stage)
        val project = try { projects.getProject(workspace.projectId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val d = digest ?: WorkspaceAnalyzer.analyze(files).also { digest = it }
        val paths = try { files.list() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }

        val requested = LinkedHashSet<String>()
        requested += d.keyFiles
        if (uiWords.containsMatchIn(userText)) requested += d.uiFiles.take(MAX_UI_FILES_SENT)
        requested += ContextSuggester.suggest(userText, paths, limit = 4)
        val bundle = ProjectContextBuilder(files).build(project?.name, project?.type, requested.take(ContextPolicy.MAX_FILES))
        return WorkspacePromptContext(instructions, WorkspacePrompts.context(d, bundle))
    }

    private companion object {
        const val MAX_UI_FILES_SENT = 4
        val uiWords = Regex("\\b(ui|ux|design|screen|layout|theme|look|interface|style|color|colour|compose|animation)\\b", RegexOption.IGNORE_CASE)
    }
}

/** One chat engine per workspace, kept while the app runs, so switching projects keeps each conversation. */
class WorkspaceHub(
    private val backend: AiBackend,
    private val sourceFiles: SourceFileRepository,
    private val projects: ProjectRepository,
    private val chatHistory: ChatHistoryStore,
    private val store: WorkspaceStore,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val engines = LinkedHashMap<String, WorkspaceChatEngine>()
    private val providers = HashMap<String, WorkspaceContextProvider>()

    /** The conversation of [workspaceId], loaded from disk on first use. Null when the workspace is unknown. */
    suspend fun engine(workspaceId: String): WorkspaceChatEngine? {
        val workspace = store.get(workspaceId) ?: return null
        val (engine, fresh) = lock.withLock {
            engines[workspaceId]?.let { return@withLock it to false }
            // Keep a few; never drop one that is still answering.
            while (engines.size >= MAX_ENGINES) {
                val idle = engines.entries.firstOrNull { !it.value.state.value.busy } ?: break
                engines.remove(idle.key)
                providers.remove(idle.key)
            }
            val provider = WorkspaceContextProvider(workspaceId, store, RepositoryAiProjectFiles(workspace.projectId, sourceFiles), projects)
            providers[workspaceId] = provider
            val created = WorkspaceChatEngine(
                workspaceId = workspaceId,
                backend = backend,
                history = chatHistory,
                promptFor = { text, stage -> provider.build(text, stage) },
                scope = scope,
            )
            engines[workspaceId] = created
            created to true
        }
        if (fresh) engine.load()
        return engine
    }

    /** Call after a sync: the next question re-reads the project. */
    suspend fun invalidate(workspaceId: String) {
        lock.withLock { providers[workspaceId]?.invalidate() }
    }

    /** Forgets the in-memory engine and the saved conversation of a workspace that was removed. */
    suspend fun discard(workspaceId: String) {
        val engine = lock.withLock {
            providers.remove(workspaceId)
            engines.remove(workspaceId)
        }
        engine?.clear() ?: chatHistory.clear("ws-$workspaceId")
    }

    private companion object { const val MAX_ENGINES = 4 }
}
