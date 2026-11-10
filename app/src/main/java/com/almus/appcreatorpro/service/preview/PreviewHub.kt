package com.almus.appcreatorpro.service.preview

import com.almus.appcreatorpro.ai.ApplyOutcome
import com.almus.appcreatorpro.ai.ChangeResolver
import com.almus.appcreatorpro.ai.GeneratedFile
import com.almus.appcreatorpro.ai.GeneratedProject
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.preview.PreviewFiles
import com.almus.appcreatorpro.preview.PreviewKind
import com.almus.appcreatorpro.preview.PreviewSession
import com.almus.appcreatorpro.preview.PreviewUiGenerator
import com.almus.appcreatorpro.preview.UiGenResult
import com.almus.appcreatorpro.service.ai.AiHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One preview session per project. It reuses the AI hub's project session, so previews read the same files as the
 * editor and the assistant, and AI-generated UI is saved through the same change manager (approval, history, undo).
 * The previews themselves only ever read.
 */
class PreviewHub(
    private val ai: AiHub,
    private val projects: ProjectRepository,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Mutex()
    private val sessions = LinkedHashMap<String, PreviewSession>()

    suspend fun session(projectId: String): PreviewSession {
        val files = ai.session(projectId).files
        return lock.withLock {
            sessions[projectId]?.let { return@withLock it }
            if (sessions.size >= MAX_SESSIONS) sessions.remove(sessions.keys.first())?.close()
            val type = try { projects.getProject(projectId)?.type } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            val s = PreviewSession(files, type, scope)
            sessions[projectId] = s
            s
        }
    }

    suspend fun aiAvailability() = ai.backend.availability()

    fun release(projectId: String) { sessions.remove(projectId)?.close() }

    /** Asks the AI for UI code for the selected target and shows the result as a draft in the preview. Nothing is saved. */
    suspend fun generate(projectId: String, kind: PreviewKind, request: String, refine: Boolean): UiGenResult {
        val preview = session(projectId)
        val st = preview.state.value
        val real = ai.session(projectId).files
        val view = PreviewFiles(real, st.draft?.files.orEmpty())
        val project = try { projects.getProject(projectId) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        val generator = PreviewUiGenerator(ai.providers.default().backend)
        val result = generator.generate(kind, request, view, listOfNotNull(st.selected?.path), if (refine) st.draft else null, project?.name, project?.type)
        if (result is UiGenResult.Ok) preview.applyDraft(result.draft)
        return result
    }

    /** Writes the draft into the project, with the same checks and history as every other AI change. Returns what happened. */
    suspend fun saveDraft(projectId: String): ApplyOutcome {
        val preview = session(projectId)
        val draft = preview.state.value.draft ?: return ApplyOutcome.Invalid("There is no draft to save.")
        val s = ai.session(projectId)
        s.init()
        val project = GeneratedProject(projectId + "-preview-" + clockMillis(), "com.example.preview", draft.summary.ifBlank { "UI from the preview" }, draft.files.map { GeneratedFile(it.key, it.value) }, draft.notes)
        val resolved = ChangeResolver.fromGenerated(project, s.files, clockMillis())
        val set = resolved.changeSet ?: return ApplyOutcome.Invalid("Nothing would change: the project already contains these files.")
        val titled = set.copy(title = "Preview UI: " + draft.summary.take(50).ifBlank { "generated screen" })
        s.changes.offer(titled)
        val outcome = s.changes.apply(titled.id, null)
        if (outcome is ApplyOutcome.Applied) preview.draftSaved() else s.changes.discard(titled.id)
        return outcome
    }

    companion object { const val MAX_SESSIONS = 3 }
}
