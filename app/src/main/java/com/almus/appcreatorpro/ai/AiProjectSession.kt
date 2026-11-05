package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface EditFlow {
    data object Idle : EditFlow
    data class Working(val stage: String, val chars: Int = 0) : EditFlow

    /** The files that WILL be sent. Nothing has left the device yet; the user confirms or changes the list. */
    data class ConfirmFiles(val instruction: String, val summary: String, val reasons: Map<String, String>, val bundle: ContextBundle) : EditFlow
    data class Done(val summary: String, val changeSetId: String?, val rejected: List<RejectedChange>, val warnings: List<String>) : EditFlow
    data class Failed(val message: String, val retryable: Boolean) : EditFlow
}

sealed interface ReviewFlow {
    data object Idle : ReviewFlow
    data class Confirm(val bundle: ContextBundle, val focus: ReviewCategory?) : ReviewFlow
    data class Working(val chars: Int = 0) : ReviewFlow
    data class Done(val result: ReviewResult, val reviewedPaths: List<String>) : ReviewFlow
    data class Failed(val message: String, val retryable: Boolean) : ReviewFlow
}

/**
 * Everything the AI assistant does for ONE project: chat, step-by-step editing, code review and change management.
 * Pure Kotlin; the ViewModels only forward to it. It is kept by the app container while the app runs.
 */
class AiProjectSession(
    val projectId: String,
    val projectName: String?,
    private val projectType: ProjectType?,
    private val backend: AiBackend,
    val files: AiProjectFiles,
    chatHistory: ChatHistoryStore,
    changeHistory: ChangeHistoryStore,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val contextBuilder = ProjectContextBuilder(files)
    private val ops = AiOperations(backend, clockMillis)

    val chat = ChatEngine(projectId, backend, chatHistory, contextFor = { paths -> contextBuilder.build(projectName, projectType, paths, includeStructure = true) }, scope = scope, clockMillis = clockMillis)
    val changes = AiChangeManager(projectId, files, changeHistory, clockMillis)

    private val _edit = MutableStateFlow<EditFlow>(EditFlow.Idle)
    val edit: StateFlow<EditFlow> = _edit.asStateFlow()
    private val _review = MutableStateFlow<ReviewFlow>(ReviewFlow.Idle)
    val review: StateFlow<ReviewFlow> = _review.asStateFlow()
    private var editJob: Job? = null
    private var reviewJob: Job? = null
    private var loaded = false

    suspend fun init() {
        if (loaded) return
        loaded = true
        chat.load()
        changes.load()
    }

    /** Files the user may attach or select (text files, no build output, no credential files). */
    suspend fun selectablePaths(): List<String> =
        files.list().filter { ContextPolicy.isTextPath(it) && !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }

    suspend fun previewContext(paths: List<String>): ContextBundle = contextBuilder.build(projectName, projectType, paths, includeStructure = false)

    // ---- editing an existing project (inspect -> choose files -> confirm -> propose -> preview -> apply) -----------------

    fun startEdit(instruction: String, manualPaths: List<String>, autoSelect: Boolean) {
        if (editJob?.isActive == true) return
        val text = instruction.trim()
        if (text.length < 5) { _edit.value = EditFlow.Failed("Describe the change you want.", false); return }
        editJob = scope.launch {
            try {
                val suggested = ContextSuggester.suggest(text, selectablePaths())
                var chosen: List<String> = (manualPaths + suggested).distinct()
                var reasons: Map<String, String> = suggested.associateWith { "Matches your description" }
                var summary = ""
                if (autoSelect) {
                    _edit.value = EditFlow.Working("Choosing which files to read...")
                    val structure = contextBuilder.build(projectName, projectType, emptyList(), includeStructure = true)
                    val known = files.list().toHashSet()
                    when (val s = ops.selectFiles(text, structure, known) { _edit.value = EditFlow.Working("Choosing which files to read...", it) }) {
                        is AiStep.Failed -> { _edit.value = EditFlow.Failed(s.message, s.retryable); return@launch }
                        is AiStep.Ok -> {
                            summary = s.value.summary
                            reasons = reasons + s.value.files.associate { it.path to it.reason.ifBlank { "Chosen by the AI" } }
                            chosen = (manualPaths + s.value.files.map { it.path } + suggested).distinct()
                        }
                    }
                }
                _edit.value = EditFlow.ConfirmFiles(text, summary, reasons, contextBuilder.build(projectName, projectType, chosen.take(ContextPolicy.MAX_FILES), includeStructure = false))
            } catch (e: CancellationException) {
                _edit.value = EditFlow.Idle
                throw e
            } catch (e: Exception) {
                _edit.value = EditFlow.Failed("The project files could not be read.", true)
            }
        }
    }

    /** The user confirmed [paths]: the files are read again (they may have changed) and sent. */
    fun confirmEdit(paths: List<String>) {
        val current = _edit.value as? EditFlow.ConfirmFiles ?: return
        if (editJob?.isActive == true) return
        editJob = scope.launch {
            try {
                _edit.value = EditFlow.Working("Asking the AI for changes...")
                val bundle = contextBuilder.build(projectName, projectType, paths, includeStructure = true)
                when (val r = ops.proposeEdit(current.instruction, bundle, files) { _edit.value = EditFlow.Working("Asking the AI for changes...", it) }) {
                    is AiStep.Failed -> _edit.value = EditFlow.Failed(r.message, r.retryable)
                    is AiStep.Ok -> {
                        val set = r.value.changeSet
                        if (set != null) changes.offer(set)
                        _edit.value = EditFlow.Done(r.value.summary, set?.id, r.value.rejected, set?.warnings.orEmpty())
                    }
                }
            } catch (e: CancellationException) {
                _edit.value = EditFlow.Idle
                throw e
            } catch (e: Exception) {
                _edit.value = EditFlow.Failed("The project files could not be read.", true)
            }
        }
    }

    fun cancelEdit() { editJob?.cancel(); _edit.value = EditFlow.Idle }
    fun resetEdit() { if (editJob?.isActive != true) _edit.value = EditFlow.Idle }

    // ---- code review -----------------------------------------------------------------------------------------------

    fun prepareReview(paths: List<String>, focus: ReviewCategory?) {
        if (reviewJob?.isActive == true) return
        scope.launch {
            try {
                val b = contextBuilder.build(projectName, projectType, paths, includeStructure = false)
                _review.value = if (b.files.isEmpty()) ReviewFlow.Failed("None of the selected files can be sent: ${b.skipped.joinToString { "${it.path} (${it.reason})" }.ifBlank { "select at least one file" }}", false)
                else ReviewFlow.Confirm(b, focus)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _review.value = ReviewFlow.Failed("The project files could not be read.", true)
            }
        }
    }

    fun confirmReview() {
        val c = _review.value as? ReviewFlow.Confirm ?: return
        if (reviewJob?.isActive == true) return
        reviewJob = scope.launch {
            try {
                _review.value = ReviewFlow.Working()
                when (val r = ops.review(c.bundle, c.focus) { _review.value = ReviewFlow.Working(it) }) {
                    is AiStep.Ok -> _review.value = ReviewFlow.Done(r.value, c.bundle.files.map { it.path })
                    is AiStep.Failed -> _review.value = ReviewFlow.Failed(r.message, r.retryable)
                }
            } catch (e: CancellationException) {
                _review.value = ReviewFlow.Idle
                throw e
            }
        }
    }

    fun cancelReview() { reviewJob?.cancel(); _review.value = ReviewFlow.Idle }
    fun resetReview() { if (reviewJob?.isActive != true) _review.value = ReviewFlow.Idle }
}
