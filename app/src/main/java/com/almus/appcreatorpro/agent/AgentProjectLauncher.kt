package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiStep
import com.almus.appcreatorpro.ai.ContextBundle
import com.almus.appcreatorpro.ai.GeneratedNames
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LauncherFlow {
    data object Idle : LauncherFlow
    data class Planning(val chars: Int = 0) : LauncherFlow
    data class PlanReady(val goal: String, val plan: AgentPlan) : LauncherFlow
    data object Creating : LauncherFlow
    data class Created(val projectId: String, val projectName: String) : LauncherFlow
    data class Failed(val message: String, val retryable: Boolean, val projectId: String? = null) : LauncherFlow
}

/**
 * Natural language project creation: describe an app -> the agent plans it -> the user approves the plan -> ONLY THEN the
 * (new, empty) project is created and the agent starts working in it. Nothing exists before the approval.
 * [startAgent] hands the approved plan to the new project's [AgentRunner].
 */
class AgentProjectLauncher(
    backend: AiBackend,
    private val projects: ProjectRepository,
    private val startAgent: suspend (projectId: String, goal: String, plan: AgentPlan) -> Unit,
    private val scope: CoroutineScope,
    private val permissions: AgentPermissions = AgentPermissions(),
    waitMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    private val planner = AgentPlanner(AgentModelClient(backend, permissions, waitMs = waitMs), permissions)
    private val _flow = MutableStateFlow<LauncherFlow>(LauncherFlow.Idle)
    val flow: StateFlow<LauncherFlow> = _flow.asStateFlow()
    private var job: Job? = null
    private val busy: Boolean get() = job?.isActive == true

    fun plan(goal: String) {
        if (busy) return
        val text = goal.trim()
        if (text.length < AgentRunner.MIN_GOAL_CHARS) { _flow.value = LauncherFlow.Failed("Describe the app in a little more detail (at least ${AgentRunner.MIN_GOAL_CHARS} characters).", false); return }
        if (text.length > AgentRunner.MAX_GOAL_CHARS) { _flow.value = LauncherFlow.Failed("The description is too long (limit ${AgentRunner.MAX_GOAL_CHARS} characters).", false); return }
        job = scope.launch {
            try {
                _flow.value = LauncherFlow.Planning()
                val empty = ContextBundle(null, "unknown", emptyList(), emptyList(), 0, emptyList(), emptyList())
                val messages = listOf(AgentMessage(AgentSpeaker.USER, text, 0L))
                when (val r = planner.plan(text, empty, messages, null, newProject = true) { n, _ -> _flow.value = LauncherFlow.Planning(n) }) {
                    is AiStep.Ok -> _flow.value = LauncherFlow.PlanReady(text, r.value)
                    is AiStep.Failed -> _flow.value = LauncherFlow.Failed(r.message, r.retryable)
                }
            } catch (e: CancellationException) {
                _flow.value = LauncherFlow.Idle
                throw e
            } catch (e: Throwable) {
                _flow.value = LauncherFlow.Failed("Planning failed: ${e.message.orEmpty().take(150).ifBlank { e.javaClass.simpleName }}", true)
            }
        }
    }

    fun removeTask(id: String) {
        val cur = _flow.value as? LauncherFlow.PlanReady ?: return
        _flow.value = cur.copy(plan = cur.plan.copy(tasks = cur.plan.tasks.filter { it.id != id }.map { it.copy(dependsOn = it.dependsOn - id) }))
    }

    /** The user approved the plan: create the project, then start the agent in it. */
    fun approveAndCreate(projectName: String, packageName: String) {
        val cur = _flow.value as? LauncherFlow.PlanReady ?: return
        if (busy || cur.plan.tasks.isEmpty()) return
        job = scope.launch {
            var createdId: String? = null
            try {
                _flow.value = LauncherFlow.Creating
                val name = GeneratedNames.projectName(projectName)
                val pkg = GeneratedNames.packageName(packageName, name)
                val type = if (cur.plan.language == "java") ProjectType.ANDROID_JAVA else ProjectType.ANDROID_KOTLIN
                val project = projects.createProject(name, pkg, cur.goal.take(200), type)
                createdId = project.id
                startAgent(project.id, cur.goal, cur.plan.copy(projectName = name, packageName = pkg))
                _flow.value = LauncherFlow.Created(project.id, project.name)
            } catch (e: CancellationException) {
                _flow.value = LauncherFlow.Idle
                throw e
            } catch (e: Throwable) {
                _flow.value = LauncherFlow.Failed(e.message?.takeIf { it.isNotBlank() } ?: "The project could not be created.", false, createdId)
            }
        }
    }

    fun cancel() { job?.cancel(); _flow.value = LauncherFlow.Idle }
    fun reset() { if (!busy) _flow.value = LauncherFlow.Idle }
}
