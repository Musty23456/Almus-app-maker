package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface GeneratorFlow {
    data object Idle : GeneratorFlow
    data class Planning(val chars: Int = 0) : GeneratorFlow

    /** The development plan, shown for review BEFORE any code is generated. */
    data class PlanReady(val description: String, val plan: ProjectPlan) : GeneratorFlow
    data class Generating(val chars: Int = 0) : GeneratorFlow

    /** Generated files, already checked. The user picks which to keep and confirms; nothing is in a project yet. */
    data class Review(val description: String, val outcome: GenerationOutcome) : GeneratorFlow
    data object Creating : GeneratorFlow
    data class Created(val projectId: String, val projectName: String, val filesWritten: Int, val warnings: List<String>) : GeneratorFlow

    /** [projectId] is set when the project was created but writing files failed: the user can still open it. */
    data class Failed(val message: String, val retryable: Boolean, val projectId: String? = null) : GeneratorFlow
}

/** "Describe an app" -> plan -> review plan -> generate -> review files -> create project. Pure Kotlin. */
class AiProjectGenerator(
    backend: AiBackend,
    private val projects: ProjectRepository,
    private val filesFor: (String) -> AiProjectFiles,
    private val changeHistory: ChangeHistoryStore,
    private val scope: CoroutineScope,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val ops = AiOperations(backend, clockMillis)
    private val _flow = MutableStateFlow<GeneratorFlow>(GeneratorFlow.Idle)
    val flow: StateFlow<GeneratorFlow> = _flow.asStateFlow()
    private var job: Job? = null

    private val busy: Boolean get() = job?.isActive == true

    fun plan(description: String) {
        if (busy) return
        job = scope.launch {
            try {
                _flow.value = GeneratorFlow.Planning()
                when (val r = ops.plan(description) { _flow.value = GeneratorFlow.Planning(it) }) {
                    is AiStep.Ok -> _flow.value = GeneratorFlow.PlanReady(description.trim(), r.value)
                    is AiStep.Failed -> _flow.value = GeneratorFlow.Failed(r.message, r.retryable)
                }
            } catch (e: CancellationException) { _flow.value = GeneratorFlow.Idle; throw e }
        }
    }

    /** The user may change the plan text (name, package) before approving it. */
    fun updatePlan(plan: ProjectPlan) {
        val cur = _flow.value as? GeneratorFlow.PlanReady ?: return
        _flow.value = cur.copy(plan = plan.copy(projectName = GeneratedNames.projectName(plan.projectName), packageName = GeneratedNames.packageName(plan.packageName, plan.projectName)))
    }

    /** [plan] null = generate directly, without a plan. */
    fun generate(description: String, plan: ProjectPlan?) {
        if (busy) return
        job = scope.launch {
            try {
                _flow.value = GeneratorFlow.Generating()
                when (val r = ops.generateProject(description, plan) { _flow.value = GeneratorFlow.Generating(it) }) {
                    is AiStep.Ok -> _flow.value = GeneratorFlow.Review(description.trim(), r.value)
                    is AiStep.Failed -> _flow.value = GeneratorFlow.Failed(r.message, r.retryable)
                }
            } catch (e: CancellationException) { _flow.value = GeneratorFlow.Idle; throw e }
        }
    }

    /**
     * Creates the project and writes the accepted files (minus [excluded]) through the change manager, so the result is
     * recorded and can be undone like any other AI change.
     */
    fun create(projectName: String, excluded: Set<String>) {
        val review = _flow.value as? GeneratorFlow.Review ?: return
        if (busy) return
        job = scope.launch {
            var createdId: String? = null
            try {
                _flow.value = GeneratorFlow.Creating
                val outcome = review.outcome
                val keep = outcome.report.accepted.filter { it.path !in excluded }
                if (keep.isEmpty()) { _flow.value = GeneratorFlow.Failed("Select at least one file.", false); return@launch }
                val name = GeneratedNames.projectName(projectName)
                val type = if (outcome.project.files.any { it.path.endsWith(".java") } && outcome.project.files.none { it.path.endsWith(".kt") }) ProjectType.ANDROID_JAVA else ProjectType.ANDROID_KOTLIN
                val project = projects.createProject(name, outcome.project.packageName, outcome.project.summary.take(200), type)
                createdId = project.id
                val files = filesFor(project.id)
                val resolved = ChangeResolver.fromGenerated(outcome.project.copy(files = keep), files, clockMillis())
                val set = resolved.changeSet
                if (set == null) { _flow.value = GeneratorFlow.Failed("No file could be written.", false, project.id); return@launch }
                val manager = AiChangeManager(project.id, files, changeHistory, clockMillis)
                manager.offer(set)
                when (val a = manager.apply(set.id)) {
                    is ApplyOutcome.Applied -> _flow.value = GeneratorFlow.Created(
                        project.id, project.name, a.record.files.size,
                        outcome.report.warnings + resolved.rejected.map { "${it.path}: ${it.reason}" },
                    )
                    is ApplyOutcome.Failed -> _flow.value = GeneratorFlow.Failed(a.reason, true, project.id)
                    is ApplyOutcome.Stale -> _flow.value = GeneratorFlow.Failed("The new project changed while it was being written.", true, project.id)
                    is ApplyOutcome.Invalid -> _flow.value = GeneratorFlow.Failed(a.reason, false, project.id)
                    ApplyOutcome.NotFound -> _flow.value = GeneratorFlow.Failed("The change was lost. Try again.", true, project.id)
                }
            } catch (e: CancellationException) {
                _flow.value = GeneratorFlow.Idle
                throw e
            } catch (e: Exception) {
                _flow.value = GeneratorFlow.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The project could not be created.", false, createdId)
            }
        }
    }

    fun cancel() { job?.cancel(); _flow.value = GeneratorFlow.Idle }
    fun reset() { if (!busy) _flow.value = GeneratorFlow.Idle }
}
