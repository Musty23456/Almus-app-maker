package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiChangeManager
import com.almus.appcreatorpro.ai.AiMessage
import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.AiRole
import com.almus.appcreatorpro.ai.AiStep
import com.almus.appcreatorpro.ai.AiTaskKind
import com.almus.appcreatorpro.ai.ApplyOutcome
import com.almus.appcreatorpro.ai.ContextBundle
import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.ContextSuggester
import com.almus.appcreatorpro.ai.Parsed
import com.almus.appcreatorpro.ai.ProjectContextBuilder
import com.almus.appcreatorpro.ai.SecretScanner
import com.almus.appcreatorpro.ai.UndoOutcome
import com.almus.appcreatorpro.domain.model.ProjectType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The autonomous development agent of ONE project. Plain Kotlin; the ViewModel only forwards to it.
 *
 * Workflow: 1-3 understand, plan, break into tasks (one request) -> the user APPROVES the plan -> 4 each task is run
 * (files are staged, never written) -> 5 validate -> 6 test checks -> 7 analyze failures -> 8 correct (staging only) ->
 * 9 build readiness -> 10 report -> the user reviews diffs and APPLIES. Applying goes through [AiChangeManager], so the
 * result is recorded and can be rolled back. Until then the real project is only ever READ.
 */
class AgentRunner(
    private val projectId: String,
    private val projectName: String?,
    private val projectType: ProjectType?,
    backend: AiBackend,
    base: AiProjectFiles,
    private val changes: AiChangeManager,
    private val store: AgentRunStore,
    private val scope: CoroutineScope,
    private val permissions: AgentPermissions = AgentPermissions(),
    private val testRunner: AgentTestRunner = StaticTestRunner(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
    waitMs: suspend (Long) -> Unit = { delay(it) },
) {
    private val staged = StagedProjectFiles(base)
    private val client = AgentModelClient(backend, permissions, { level, text -> log(level, text) }, waitMs)
    private val planner = AgentPlanner(client, permissions)
    private val validator = AgentValidator()
    private val applier = StagingApplier(staged, permissions)
    private val ctx = ProjectContextBuilder(staged)

    private val _state = MutableStateFlow(AgentRunState())
    val state: StateFlow<AgentRunState> = _state.asStateFlow()
    private var job: Job? = null
    private var epoch = 0

    private sealed interface Converse {
        data class Ok(val summary: String, val applied: List<String>, val notes: List<String>, val dependencies: List<AgentDependencyAdvice>, val rejected: List<AgentRejection>) : Converse
        data class Failed(val message: String, val retryable: Boolean) : Converse
    }

    private data class CheckResult(val validation: AgentValidation, val tests: AgentTestOutcome, val failures: List<AgentFailure>) {
        val errorCount: Int get() = failures.sumOf { it.problems.size }
    }

    private val active: Boolean get() = job?.isActive == true

    // ------------------------------------------------------------------------------------------------ small helpers

    private fun log(level: AgentLogLevel, text: String, taskId: String? = null) {
        val entry = AgentLogEntry(clockMillis(), level, SecretScanner.redact(text).text.take(600), taskId)
        _state.update { it.copy(logs = (it.logs + entry).takeLast(MAX_LOGS)) }
    }

    private fun chunk(chars: Int, tail: String) {
        _state.update { it.copy(streamedChars = chars, liveTail = SecretScanner.redact(tail).text) }
    }

    private fun syncCalls() { _state.update { it.copy(modelCalls = client.calls) } }

    private fun setStage(stage: AgentStage, phase: AgentPhase = AgentPhase.RUNNING) {
        _state.update { it.copy(stage = stage, phase = phase, error = null) }
    }

    private fun updateTask(id: String, change: (AgentTask) -> AgentTask) {
        _state.update { s ->
            val p = s.plan ?: return@update s
            s.copy(plan = p.copy(tasks = p.tasks.map { if (it.id == id) change(it) else it }))
        }
    }

    private fun resetRunning(plan: AgentPlan): AgentPlan =
        plan.copy(tasks = plan.tasks.map { if (it.status == TaskStatus.RUNNING) it.copy(status = TaskStatus.PENDING) else it })

    private fun mergeDeps(a: List<AgentDependencyAdvice>, b: List<AgentDependencyAdvice>): List<AgentDependencyAdvice> =
        (a + b).distinctBy { it.coordinate }.take(40)

    private fun fail(message: String, retryable: Boolean) {
        log(AgentLogLevel.ERROR, message)
        _state.update { s ->
            val phase = if (!s.planApproved && s.plan != null) AgentPhase.AWAITING_APPROVAL else AgentPhase.FAILED
            s.copy(phase = phase, error = message, retryable = retryable, currentTaskId = null, streamedChars = 0, liveTail = "")
        }
    }

    private fun onCancelled() {
        _state.update { s ->
            val phase = if (!s.planApproved && s.plan != null) AgentPhase.AWAITING_APPROVAL else AgentPhase.CANCELLED
            s.copy(phase = phase, currentTaskId = null, streamedChars = 0, liveTail = "", plan = s.plan?.let { resetRunning(it) }, error = null)
        }
        log(AgentLogLevel.WARN, "Cancelled. Nothing was written to your project. Work done so far is kept and can be resumed.")
    }

    private fun launch(block: suspend () -> Unit) {
        val mine = epoch
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                if (mine == epoch) onCancelled()
                throw e
            } catch (e: Throwable) {
                if (mine == epoch) fail("The agent stopped unexpectedly (${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}).", true)
            } finally {
                if (mine == epoch) withContext(NonCancellable) { persist() }
            }
        }
    }

    private suspend fun persist() {
        val s = _state.value
        try {
            val id = s.runId
            if (id == null) { store.clear(projectId); return }
            val snap = staged.snapshot()
            store.save(
                projectId,
                AgentSnapshot(
                    runId = id, goal = s.goal, phase = s.phase, stage = s.stage, plan = s.plan, planApproved = s.planApproved, messages = s.messages,
                    logs = s.logs.takeLast(PERSISTED_LOGS), options = s.options, staged = snap.content, baseHashes = snap.hashes.filterKeys { it in snap.content },
                    recommendedDependencies = s.recommendedDependencies, rejections = s.rejections, corrections = s.corrections,
                    reportMarkdown = s.report?.markdown, modelCalls = s.modelCalls, startedAtMillis = s.startedAtMillis, finishedAtMillis = s.finishedAtMillis,
                    error = s.error, appliedRecordId = s.appliedRecordId,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Saved progress is a convenience; the staged work stays in memory and nothing in the project depends on it.
        }
    }

    // ------------------------------------------------------------------------------------------------ restore

    /** Brings back the last run of this project (progress, plan, staged files). A run that was cut off by closing the app can be resumed. */
    suspend fun load() {
        if (_state.value.runId != null) return
        val snap = try { store.load(projectId) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        if (snap == null) return
        staged.restore(snap.staged, snap.baseHashes)
        client.restoreCalls(snap.modelCalls)
        val stale = staged.staleAgainstBase()
        var s = AgentRunState(
            runId = snap.runId, goal = snap.goal, phase = snap.phase, stage = snap.stage, plan = snap.plan, planApproved = snap.planApproved,
            logs = snap.logs, messages = snap.messages, corrections = snap.corrections, recommendedDependencies = snap.recommendedDependencies,
            rejections = snap.rejections, options = snap.options, modelCalls = snap.modelCalls, startedAtMillis = snap.startedAtMillis,
            finishedAtMillis = snap.finishedAtMillis, error = snap.error, appliedRecordId = snap.appliedRecordId,
        )
        if (snap.phase == AgentPhase.RUNNING || snap.phase == AgentPhase.PLANNING) {
            val back = if (!snap.planApproved && snap.plan != null) AgentPhase.AWAITING_APPROVAL else AgentPhase.CANCELLED
            s = s.copy(phase = back, plan = s.plan?.let { resetRunning(it) }, error = "The app was closed while the agent was working. Resume to continue; finished work was kept.")
        }
        if (snap.phase == AgentPhase.APPLIED && snap.reportMarkdown != null) {
            s = s.copy(report = AgentReport(snap.reportMarkdown, ReportOutcome.READY, 0, 0, 0, 0))
        }
        _state.value = s
        for (p in stale) {
            staged.discard(p)
            log(AgentLogLevel.WARN, "$p changed in the project after the agent worked on it, so the agent's version of it was dropped.")
        }
        if (s.phase == AgentPhase.AWAITING_REVIEW) refreshReview()
    }

    private suspend fun refreshReview() {
        val v = validator.validate(staged)
        val t = testRunner.run(staged, staged.stagedPaths())
        val readiness = BuildPreparation.check(AgentValidator.mergedTexts(staged), projectType, staged.stagedPaths().toSet())
        val s = _state.value
        val built = staged.buildChangeSet(s.runId.orEmpty(), "Agent: ${s.goal.take(50)}", s.plan?.summary.orEmpty(), clockMillis())
        val full = s.copy(validation = v, testOutcome = t, readiness = readiness, changeSet = built.changeSet)
        val report = AgentReportBuilder.build(full, built.changeSet, clockMillis())
        _state.update { it.copy(validation = v, testOutcome = t, readiness = readiness, changeSet = built.changeSet, report = report) }
    }

    // ------------------------------------------------------------------------------------------------ starting

    /** Steps 1-3. Refused (false) while a run is working, or while a run with a plan has not been applied or discarded. */
    fun start(goal: String, options: AgentOptions = AgentOptions()): Boolean {
        if (active) return false
        val s = _state.value
        if (s.plan != null && s.phase != AgentPhase.APPLIED) return false
        val text = goal.trim()
        if (text.length < MIN_GOAL_CHARS) { _state.update { it.copy(error = "Describe what you want in a little more detail (at least $MIN_GOAL_CHARS characters).") }; return false }
        if (text.length > MAX_GOAL_CHARS) { _state.update { it.copy(error = "The description is too long (limit $MAX_GOAL_CHARS characters).") }; return false }
        staged.clear()
        client.restoreCalls(0)
        val now = clockMillis()
        _state.value = AgentRunState(
            runId = newId(), goal = text, phase = AgentPhase.PLANNING, stage = AgentStage.UNDERSTAND, options = options, startedAtMillis = now,
            messages = listOf(AgentMessage(AgentSpeaker.USER, text, now)),
        )
        launch { planPhase(null) }
        return true
    }

    /** For a NEW project whose plan the user already approved (see [AgentProjectLauncher]): goes straight to step 4. */
    fun startWithPlan(goal: String, plan: AgentPlan, options: AgentOptions = AgentOptions()): Boolean {
        if (active || plan.tasks.isEmpty()) return false
        staged.clear()
        client.restoreCalls(0)
        val now = clockMillis()
        _state.value = AgentRunState(
            runId = newId(), goal = goal.trim(), phase = AgentPhase.RUNNING, stage = AgentStage.EXECUTE, plan = plan, planApproved = true,
            recommendedDependencies = plan.dependencies, options = options, startedAtMillis = now, messages = listOf(AgentMessage(AgentSpeaker.USER, goal.trim(), now)),
        )
        log(AgentLogLevel.STEP, "The plan was approved when the project was created. Starting with step 4.")
        launch { executionPhase() }
        return true
    }

    private suspend fun planPhase(previous: AgentPlan?) {
        val s0 = _state.value
        log(AgentLogLevel.STEP, "Step 1: ${AgentStage.UNDERSTAND.label}")
        val bundle = ctx.build(projectName, projectType, emptyList(), includeStructure = true)
        log(AgentLogLevel.INFO, "The project has ${bundle.structure.size + bundle.structureOmitted} file(s) and ${bundle.dependencies.size} known dependencies.")
        setStage(AgentStage.PLAN, AgentPhase.PLANNING)
        log(AgentLogLevel.STEP, "Step 2: asking the AI for a development plan")
        val r = planner.plan(s0.goal, bundle, s0.messages, previous, newProject = false) { n, t -> chunk(n, t) }
        syncCalls()
        when (r) {
            is AiStep.Failed -> {
                if (previous != null) {
                    log(AgentLogLevel.ERROR, "The plan could not be changed: ${r.message}")
                    _state.update { it.copy(phase = AgentPhase.AWAITING_APPROVAL, stage = AgentStage.BREAKDOWN, error = r.message, streamedChars = 0, liveTail = "") }
                } else fail(r.message, r.retryable)
            }
            is AiStep.Ok -> {
                val plan = r.value
                plan.warnings.forEach { log(AgentLogLevel.WARN, it) }
                _state.update { it.copy(plan = plan, planApproved = false, stage = AgentStage.BREAKDOWN, phase = AgentPhase.AWAITING_APPROVAL, recommendedDependencies = plan.dependencies, streamedChars = 0, liveTail = "", error = null) }
                log(AgentLogLevel.STEP, "Step 3: ${plan.tasks.size} task(s) planned. Waiting for your approval.")
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ plan editing and approval

    private fun editPlan(block: (AgentPlan) -> AgentPlan) {
        if (_state.value.phase != AgentPhase.AWAITING_APPROVAL || active) return
        _state.update { s -> s.plan?.let { s.copy(plan = block(it)) } ?: s }
    }

    fun removeTask(id: String) = editPlan { p ->
        p.copy(tasks = p.tasks.filter { it.id != id }.map { it.copy(dependsOn = it.dependsOn - id) })
    }

    fun editTask(id: String, title: String, detail: String) = editPlan { p ->
        p.copy(tasks = p.tasks.map { if (it.id == id) it.copy(title = title.trim().ifEmpty { it.title }.take(100), detail = detail.trim().take(800)) else it })
    }

    fun setAutoCorrect(on: Boolean) { _state.update { it.copy(options = it.options.copy(autoCorrect = on)) } }

    /** Steps 4-10 start only here. */
    fun approvePlan(): Boolean {
        val s = _state.value
        val plan = s.plan
        if (s.phase != AgentPhase.AWAITING_APPROVAL || plan == null || plan.tasks.isEmpty() || active) return false
        _state.update { it.copy(planApproved = true, phase = AgentPhase.RUNNING, stage = AgentStage.EXECUTE, error = null) }
        log(AgentLogLevel.INFO, "Plan approved by you.")
        launch { executionPhase() }
        return true
    }

    /** A note or an answer: kept in the conversation and sent with every later request. While a plan awaits approval it asks for a new plan. */
    fun sendMessage(text: String): Boolean {
        val clean = text.trim()
        val s = _state.value
        if (clean.isEmpty() || s.runId == null) return false
        return if (s.phase == AgentPhase.AWAITING_APPROVAL) revisePlan(clean) else addNote(clean)
    }

    private fun addNote(text: String): Boolean {
        val msg = AgentMessage(AgentSpeaker.USER, text.take(1000), clockMillis())
        _state.update { it.copy(messages = (it.messages + msg).takeLast(MAX_MESSAGES)) }
        return true
    }

    fun revisePlan(feedback: String): Boolean {
        val s = _state.value
        val text = feedback.trim()
        if (s.phase != AgentPhase.AWAITING_APPROVAL || text.isEmpty() || active) return false
        val msg = AgentMessage(AgentSpeaker.USER, text.take(1000), clockMillis())
        _state.update { it.copy(messages = (it.messages + msg).takeLast(MAX_MESSAGES), phase = AgentPhase.PLANNING, stage = AgentStage.PLAN, error = null) }
        launch { planPhase(_state.value.plan) }
        return true
    }

    // ------------------------------------------------------------------------------------------------ cancel / resume / discard

    fun cancel() { if (active) job?.cancel() }

    /** After a failure or a cancel: continues with the first unfinished task; finished tasks and staged files are kept. */
    fun retry(): Boolean {
        val s = _state.value
        if (active || (s.phase != AgentPhase.FAILED && s.phase != AgentPhase.CANCELLED)) return false
        if (!s.planApproved || s.plan == null) {
            if (s.goal.isBlank()) return false
            _state.update { it.copy(phase = AgentPhase.PLANNING, stage = AgentStage.PLAN, error = null) }
            launch { planPhase(s.plan) }
            return true
        }
        _state.update { it.copy(phase = AgentPhase.RUNNING, stage = AgentStage.EXECUTE, error = null) }
        launch { executionPhase() }
        return true
    }

    /** Gives up on the failed task(s) (tasks that depend on them are skipped too) and goes on. */
    fun skipFailedTasks(): Boolean {
        val s = _state.value
        if (active || s.plan == null) return false
        _state.update { st -> st.plan?.let { p -> st.copy(plan = p.copy(tasks = p.tasks.map { if (it.status == TaskStatus.FAILED) it.copy(status = TaskStatus.SKIPPED, note = "Skipped by you. ${it.note}".take(300)) else it })) } ?: st }
        return retry()
    }

    /** Throws the run away, staged files included. The project was never touched, so nothing needs to be restored. */
    fun discardRun() {
        epoch++
        job?.cancel()
        job = null
        staged.clear()
        client.restoreCalls(0)
        _state.value = AgentRunState()
        scope.launch { try { store.clear(projectId) } catch (e: CancellationException) { throw e } catch (e: Exception) { /* nothing to do */ } }
    }

    fun dismissError() { _state.update { it.copy(error = null) } }

    // ------------------------------------------------------------------------------------------------ step 4

    private suspend fun executionPhase() {
        setStage(AgentStage.EXECUTE)
        while (true) {
            val s = _state.value
            val task = s.tasks.firstOrNull { it.status == TaskStatus.PENDING || it.status == TaskStatus.FAILED || it.status == TaskStatus.RUNNING } ?: break
            val unmet = task.dependsOn.filter { dep -> s.tasks.firstOrNull { it.id == dep }?.status != TaskStatus.DONE }
            if (unmet.isNotEmpty()) {
                updateTask(task.id) { it.copy(status = TaskStatus.SKIPPED, note = "Skipped: it depends on ${unmet.joinToString()}, which did not finish.") }
                log(AgentLogLevel.WARN, "Task ${task.id} skipped because ${unmet.joinToString()} did not finish.", task.id)
                continue
            }
            if (!runTask(task.id)) return
        }
        val autoCorrect = _state.value.options.autoCorrect
        var check = runChecks()
        if (autoCorrect) check = correctionLoop(check)
        else if (check.failures.any { it.fixable }) log(AgentLogLevel.INFO, "Step 8: problems were found. Use \"Fix problems\" to let the AI correct them.")
        finish()
    }

    private suspend fun runTask(id: String): Boolean {
        val before = _state.value.tasks.first { it.id == id }
        val previousProblem = if (before.status == TaskStatus.FAILED) before.note else null
        updateTask(id) { it.copy(status = TaskStatus.RUNNING, attempts = it.attempts + 1, note = "") }
        _state.update { it.copy(currentTaskId = id, streamedChars = 0, liveTail = "") }
        val task = _state.value.tasks.first { it.id == id }
        log(AgentLogLevel.STEP, "Step 4: task $id - ${task.title}", id)
        val outcome = executeTask(task, previousProblem)
        syncCalls()
        return when (outcome) {
            is Converse.Ok -> {
                updateTask(id) { it.copy(status = TaskStatus.DONE, note = outcome.summary.take(300), touched = outcome.applied) }
                _state.update { it.copy(rejections = (it.rejections + outcome.rejected).takeLast(100), recommendedDependencies = mergeDeps(it.recommendedDependencies, outcome.dependencies), currentTaskId = null, streamedChars = 0, liveTail = "") }
                outcome.notes.forEach { log(AgentLogLevel.INFO, "Note: $it", id) }
                outcome.rejected.forEach { log(AgentLogLevel.WARN, "Not applied: ${it.path} - ${it.reason}", id) }
                log(AgentLogLevel.INFO, "Task $id done: ${outcome.applied.size} file(s) staged.", id)
                persist()
                true
            }
            is Converse.Failed -> {
                updateTask(id) { it.copy(status = TaskStatus.FAILED, note = outcome.message.take(400)) }
                log(AgentLogLevel.ERROR, "Task $id failed: ${outcome.message}", id)
                fail("Task $id (${task.title}) failed: ${outcome.message}", outcome.retryable)
                false
            }
        }
    }

    private suspend fun candidatePaths(task: AgentTask, plan: AgentPlan): List<String> {
        val all = staged.list()
        val known = all.toHashSet()
        val selectable = all.filter { ContextPolicy.isTextPath(it) && !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }
        val out = LinkedHashSet<String>()
        task.paths.filter { it in known }.forEach { out += it }
        for (dep in task.dependsOn) plan.tasks.firstOrNull { it.id == dep }?.touched?.filter { it in known }?.forEach { out += it }
        if (task.kind == AgentTaskKind.DEPENDENCIES || task.kind == AgentTaskKind.DATABASE || task.kind == AgentTaskKind.API || task.kind == AgentTaskKind.TESTS) {
            listOf("app/build.gradle.kts", "app/build.gradle").filter { it in known }.forEach { out += it }
        }
        // A new project starts from template files; a structure task must be shown them to be allowed to change them.
        if (task.kind == AgentTaskKind.SCAFFOLD) selectable.take(ContextPolicy.MAX_FILES).forEach { out += it }
        if (task.kind == AgentTaskKind.API || task.kind == AgentTaskKind.UI) {
            all.firstOrNull { it.endsWith("AndroidManifest.xml") && !ContextPolicy.isHiddenPath(it) }?.let { out += it }
        }
        ContextSuggester.suggest(task.title + " " + task.detail, selectable).forEach { out += it }
        val allowed = selectable.toHashSet()
        return out.filter { it in allowed }.take(ContextPolicy.MAX_FILES)
    }

    private suspend fun executeTask(task: AgentTask, previousProblem: String?): Converse {
        val s = _state.value
        val plan = s.plan ?: return Converse.Failed("There is no plan.", false)
        val bundle = ctx.build(projectName, projectType, candidatePaths(task, plan), includeStructure = true)
        bundle.skipped.forEach { log(AgentLogLevel.INFO, "Not sent to the AI: ${it.path} (${it.reason})", task.id) }
        log(AgentLogLevel.INFO, "Sending ${bundle.files.size} file(s) of project context.", task.id)
        val prompt = AgentPrompts.taskRequest(s.goal, plan, task, s.messages, previousProblem, bundle)
        return converse(AiTaskKind.AGENT_TASK, prompt, bundle, task.id, mayWrite = task.kind.writesFiles)
    }

    /** One request/answer exchange with tool calling: the model may ask to read files first, then answers with changes, which are STAGED. */
    private suspend fun converse(aiTask: AiTaskKind, prompt: String, bundle: ContextBundle, taskId: String?, mayWrite: Boolean): Converse {
        val toolbox = AgentToolbox(staged, permissions, bundle.files.map { it.path }.toMutableSet(), bundle.files.filter { it.truncated }.map { it.path }.toMutableSet())
        val messages = ArrayList<AiMessage>()
        messages += AiMessage(AiRole.USER, prompt)
        var toolRounds = 0
        var parseRetries = 0
        while (true) {
            val completion = when (val r = client.ask(aiTask, messages) { n, t -> chunk(n, t) }) {
                is AiStep.Ok -> r.value
                is AiStep.Failed -> return Converse.Failed(r.message, r.retryable)
            }
            syncCalls()
            val result = when (val p = AgentTaskResultParser.parse(completion.text, completion.truncated)) {
                is Parsed.Ok -> p.value
                is Parsed.Bad -> {
                    if (parseRetries >= permissions.maxParseRetries) return Converse.Failed(p.reason, true)
                    parseRetries++
                    log(AgentLogLevel.WARN, "The answer could not be used (${p.reason}). Asking again.", taskId)
                    messages += AiMessage(AiRole.MODEL, completion.text.take(1500).ifBlank { "(empty answer)" })
                    messages += AiMessage(AiRole.USER, AgentPrompts.retryHint(p.reason, completion.truncated))
                    continue
                }
            }
            if (result.changes.isEmpty() && result.toolCalls.isNotEmpty()) {
                if (toolRounds >= permissions.maxToolRoundsPerTask) return Converse.Failed("The AI kept asking to read files instead of answering.", true)
                toolRounds++
                val outputs = toolbox.run(result.toolCalls)
                outputs.forEach { log(if (it.error) AgentLogLevel.WARN else AgentLogLevel.INFO, "Tool ${it.tool}: ${it.summary}", taskId) }
                messages += AiMessage(AiRole.MODEL, completion.text.take(2000).ifBlank { "(empty answer)" })
                messages += AiMessage(AiRole.USER, AgentPrompts.toolResults(outputs))
                continue
            }
            if (!mayWrite) {
                if (result.changes.isNotEmpty()) log(AgentLogLevel.WARN, "This kind of task does not change files; ${result.changes.size} proposed change(s) were ignored.", taskId)
                return Converse.Ok(result.summary.ifBlank { "Done." }, emptyList(), result.notes, result.dependencies, emptyList())
            }
            val staging = applier.apply(result.changes, toolbox.readable, toolbox.truncated, taskId)
            if (result.changes.isNotEmpty() && staging.applied.isEmpty()) {
                return Converse.Failed("No file could be changed: " + staging.rejected.joinToString("; ") { "${it.path}: ${it.reason}" }.take(400), true)
            }
            if (result.changes.isEmpty()) log(AgentLogLevel.WARN, "The AI proposed no file changes for this task.", taskId)
            return Converse.Ok(result.summary.ifBlank { "Done." }, staging.applied, result.notes, result.dependencies, staging.rejected)
        }
    }

    // ------------------------------------------------------------------------------------------------ steps 5-7

    private suspend fun runChecks(): CheckResult {
        setStage(AgentStage.VALIDATE)
        log(AgentLogLevel.STEP, "Step 5: validating ${staged.stagedPaths().size} file(s)")
        var validation = validator.validate(staged)
        if (validation.rejectedPaths.isNotEmpty()) {
            for (p in validation.rejectedPaths) {
                val why = validation.problems.firstOrNull { it.path == p }?.message.orEmpty()
                staged.discard(p)
                log(AgentLogLevel.WARN, "Removed $p from the result: $why")
                _state.update { it.copy(rejections = it.rejections + AgentRejection(p, why)) }
            }
            validation = validator.validate(staged)
        }
        setStage(AgentStage.TEST)
        log(AgentLogLevel.STEP, "Step 6: checking tests")
        val tests = testRunner.run(staged, staged.stagedPaths())
        log(AgentLogLevel.INFO, tests.summary)
        setStage(AgentStage.ANALYZE_FAILURES)
        val failures = AgentFailureAnalyzer.analyze(validation.problems + tests.problems, staged.list().toHashSet())
        log(AgentLogLevel.STEP, "Step 7: ${if (failures.isEmpty()) "no errors found" else "${failures.sumOf { it.problems.size }} error(s) in ${failures.size} file(s)"}")
        _state.update { it.copy(validation = validation, testOutcome = tests) }
        return CheckResult(validation, tests, failures)
    }

    // ------------------------------------------------------------------------------------------------ step 8

    private suspend fun fixOnce(failures: List<AgentFailure>): Converse {
        val bundle = ctx.build(projectName, projectType, failures.map { it.path }, includeStructure = false)
        return converse(AiTaskKind.AGENT_FIX, AgentPrompts.fixRequest(failures, bundle), bundle, null, mayWrite = true)
    }

    /**
     * Corrections change the STAGING area only. A round that makes the error count worse is undone, and at most
     * [AgentPermissions.maxCorrectionRounds] rounds run.
     */
    private suspend fun correctionLoop(initial: CheckResult): CheckResult {
        var check = initial
        var round = 0
        if (initial.failures.none { it.fixable }) log(AgentLogLevel.STEP, "Step 8: no corrections needed")
        while (round < permissions.maxCorrectionRounds) {
            val fixable = check.failures.filter { it.fixable }.take(MAX_FIX_FILES)
            if (fixable.isEmpty()) break
            round++
            setStage(AgentStage.CORRECT)
            val before = check.errorCount
            log(AgentLogLevel.STEP, "Step 8: correction round $round for ${fixable.size} file(s)")
            val snap = staged.snapshot()
            val outcome = fixOnce(fixable)
            syncCalls()
            when (outcome) {
                is Converse.Failed -> { log(AgentLogLevel.ERROR, "The correction failed: ${outcome.message}"); break }
                is Converse.Ok -> {
                    _state.update { it.copy(rejections = (it.rejections + outcome.rejected).takeLast(100)) }
                    if (outcome.applied.isEmpty()) { log(AgentLogLevel.WARN, "The AI proposed no usable correction."); break }
                    val after = runChecks()
                    val worse = after.errorCount > before
                    if (worse) {
                        staged.restore(snap)
                        log(AgentLogLevel.WARN, "Correction round $round made things worse ($before -> ${after.errorCount} errors) and was undone.")
                        _state.update { it.copy(corrections = it.corrections + AgentCorrection(round, outcome.applied, outcome.summary, before, after.errorCount, true)) }
                        check = runChecks()
                        break
                    }
                    _state.update { it.copy(corrections = it.corrections + AgentCorrection(round, outcome.applied, outcome.summary, before, after.errorCount, false)) }
                    check = after
                }
            }
        }
        return check
    }

    /** The user asked the agent to correct the problems it found. */
    fun fixProblems(): Boolean {
        if (active || _state.value.phase != AgentPhase.AWAITING_REVIEW) return false
        _state.update { it.copy(phase = AgentPhase.RUNNING, stage = AgentStage.VALIDATE, error = null, finishedAtMillis = null) }
        launch {
            val c = runChecks()
            correctionLoop(c)
            finish()
        }
        return true
    }

    // ------------------------------------------------------------------------------------------------ steps 9-10

    private suspend fun finish() {
        setStage(AgentStage.PREPARE_BUILD)
        log(AgentLogLevel.STEP, "Step 9: preparing for the build")
        val readiness = BuildPreparation.check(AgentValidator.mergedTexts(staged), projectType, staged.stagedPaths().toSet())
        readiness.failures.forEach { log(AgentLogLevel.WARN, "Build check failed: ${it.label} - ${it.detail}") }
        setStage(AgentStage.REPORT)
        log(AgentLogLevel.STEP, "Step 10: writing the completion report")
        val s = _state.value
        val built = staged.buildChangeSet(s.runId.orEmpty(), "Agent: ${s.goal.take(50)}", s.plan?.summary.orEmpty(), clockMillis())
        built.rejected.forEach { log(AgentLogLevel.WARN, "Not part of the result: ${it.path} - ${it.reason}") }
        val finished = clockMillis()
        val full = _state.value.copy(readiness = readiness, changeSet = built.changeSet, finishedAtMillis = finished, rejections = _state.value.rejections + built.rejected.map { AgentRejection(it.path, it.reason) })
        val report = AgentReportBuilder.build(full, built.changeSet, finished)
        _state.update {
            it.copy(
                readiness = readiness, changeSet = built.changeSet, finishedAtMillis = finished, report = report, rejections = full.rejections,
                phase = AgentPhase.AWAITING_REVIEW, currentTaskId = null, streamedChars = 0, liveTail = "", error = null,
            )
        }
        log(AgentLogLevel.INFO, "Ready for your review. Nothing has been written to your project.")
    }

    // ------------------------------------------------------------------------------------------------ apply and roll back

    /** The only place where the agent's files reach the project, and only with the user's approval, through the change manager. */
    suspend fun applyToProject(selected: Set<String>?): ApplyOutcome {
        val s = _state.value
        val set = s.changeSet ?: return ApplyOutcome.Invalid("There is nothing to apply.")
        if (s.phase != AgentPhase.AWAITING_REVIEW) return ApplyOutcome.Invalid("The result is not ready to be applied.")
        if (active) return ApplyOutcome.Invalid("The agent is still working.")
        changes.offer(set)
        val r = changes.apply(set.id, selected)
        if (r is ApplyOutcome.Applied) {
            _state.update { it.copy(phase = AgentPhase.APPLIED, appliedRecordId = r.record.id) }
            log(AgentLogLevel.INFO, "Applied ${r.record.files.size} file(s) to the project. You can undo this.")
            persist()
        } else {
            changes.discard(set.id)
        }
        return r
    }

    /** Puts the project files back as they were before [applyToProject]. Refuses (Conflict) when files were edited since, unless [force]. */
    suspend fun rollbackApplied(force: Boolean = false): UndoOutcome {
        val id = _state.value.appliedRecordId ?: return UndoOutcome.NotFound
        val r = changes.undo(id, force)
        if (r is UndoOutcome.Undone) {
            changes.discard(id)
            _state.update { it.copy(phase = AgentPhase.AWAITING_REVIEW, appliedRecordId = null) }
            log(AgentLogLevel.INFO, "The applied files were rolled back. The result is available for review again.")
            persist()
        }
        return r
    }

    companion object {
        const val MIN_GOAL_CHARS = 15
        const val MAX_GOAL_CHARS = 4000
        const val MAX_LOGS = 400
        const val PERSISTED_LOGS = 200
        const val MAX_MESSAGES = 30
        const val MAX_FIX_FILES = 8
    }
}
