package com.almus.appcreatorpro.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** The raw build log handed to a session, or why there is none. */
data class RawBuildLog(val text: String?, val note: String?)

enum class IssueFilter(val label: String) { ALL("All"), BUILD("Build log"), STATIC("Code checks") }

data class DebugState(
    val loadingLog: Boolean = false,
    val runningChecks: Boolean = false,
    val logLoaded: Boolean = false,
    val logNote: String? = null,
    val buildFailed: Boolean? = null,
    val buildIssues: List<BuildIssue> = emptyList(),
    /** Phase 22: what the build issues mean together (root cause, secondary errors, severity, files, confidence). */
    val diagnosis: Diagnosis = Diagnosis.EMPTY,
    val omittedWarnings: Int = 0,
    val staticFindings: List<Finding> = emptyList(),
    val filesChecked: Int = 0,
    val checksRan: Boolean = false,
    val entries: List<LogEntry> = emptyList(),
    val logFilter: LogFilter = LogFilter(),
    val issueFilter: IssueFilter = IssueFilter.ALL,
    val message: String? = null,
) {
    val staticIssues: List<BuildIssue> get() = staticFindings.map { it.issue }

    /** Issues for the dashboard, most severe first, then in the order they were found. */
    val issues: List<BuildIssue>
        get() {
            val all = when (issueFilter) {
                IssueFilter.ALL -> buildIssues + staticIssues
                IssueFilter.BUILD -> buildIssues
                IssueFilter.STATIC -> staticIssues
            }
            return all.sortedBy { it.severity.rank }
        }

    val visibleLog: List<LogEntry> get() = logFilter.apply(entries)
    val logStats: LogStats get() = LogStats.of(entries)
}

/**
 * Everything the debug screens need, without Android: build-log analysis, static checks, the log viewer's filter,
 * suggested fixes (rule based and, later, AI) and navigation targets. The ViewModel only forwards to this class.
 * No network access and no AI call happens here; [askAi] runs only when the user presses the button.
 */
class DebugSession(
    val projectName: String?,
    val buildId: String?,
    private val files: ProjectFileAccess,
    private val listPaths: suspend () -> List<String>,
    private val loadLog: suspend () -> RawBuildLog,
    val ai: AiDebugProvider = NoAiProvider,
    val logcat: LogcatSource = UnavailableLogcatSource,
    /** Receives every unexpected exception that was turned into a message, so none is lost (default: stderr / Logcat). */
    private val errorSink: (String, Throwable) -> Unit = DefaultErrorSink,
) {
    val fixes = FixManager(files)

    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state.asStateFlow()

    private var pathsCache: List<String> = emptyList()

    /** The last AI diagnosis and what it was built from, so a follow-up question can refer to it. Null before the first answer. */
    private class StoredAnswer(val response: AiDebugResponse, val request: AiDebugRequest, val offered: List<FixProposal>, val rejected: Int)

    @Volatile private var lastAnswer: StoredAnswer? = null

    /**
     * Reads and analyses the build log. Safe to call again (refresh). The analysis of a large log runs on a background
     * dispatcher (never on the main thread), the log is bounded by [LogLimits], and a failure becomes a note on the
     * screen instead of an exception. [DebugState.loadingLog] is always reset, also when the work is cancelled.
     */
    suspend fun loadBuildLog() {
        if (buildId == null) {
            _state.update { it.copy(logLoaded = true, logNote = "No build is selected. Use the code checks, or open a build and choose \"Analyze errors\".") }
            return
        }
        _state.update { it.copy(loadingLog = true, message = null) }
        try {
            val raw = try {
                loadLog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errorSink("The build log could not be loaded", e)
                RawBuildLog(null, "The log could not be loaded: ${e.message.orEmpty()}")
            }
            val text = raw.text
            if (text.isNullOrBlank()) {
                _state.update { it.copy(loadingLog = false, logLoaded = true, logNote = raw.note ?: "This build has no log yet.", buildIssues = emptyList(), entries = emptyList()) }
                return
            }
            val paths = try { listPaths() } catch (e: CancellationException) { throw e } catch (e: Exception) { errorSink("The project file list could not be read", e); emptyList() }
            pathsCache = paths
            val bounded = LogLimits.bound(text)
            val analysed = try {
                withContext(Dispatchers.Default) {
                    val run = DiagnosisEngine(ProjectPathResolver(paths), paths).run(bounded.text, truncated = bounded.truncated)
                    run to LogParser.parse(bounded.text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!AiFailures.isRecoverable(e)) throw e
                errorSink("The build log could not be analysed", e)
                _state.update {
                    it.copy(loadingLog = false, logLoaded = true, buildIssues = emptyList(), entries = emptyList(),
                        logNote = "The log could not be analysed (${e.javaClass.simpleName}). It may be too large for this phone.")
                }
                return
            }
            val (run, entries) = analysed
            val result = run.analysis
            // A new analysis replaces the old one, and so do answers that were about the old one.
            lastAnswer = null
            _state.update {
                it.copy(
                    loadingLog = false, logLoaded = true, logNote = listOfNotNull(raw.note, bounded.note).joinToString(" ").ifBlank { null },
                    buildFailed = result.buildFailed, buildIssues = result.issues, diagnosis = run.diagnosis,
                    omittedWarnings = result.omittedWarnings, entries = entries,
                )
            }
        } finally {
            // Cancelled (the screen was left) or failed half way: never leave the spinner on.
            _state.update { if (it.loadingLog) it.copy(loadingLog = false) else it }
        }
    }

    /** Static checks over the project's text files. These are heuristics, not a compiler (see [Confidence]). */
    suspend fun runStaticChecks() {
        _state.update { it.copy(runningChecks = true, message = null) }
        val map = LinkedHashMap<String, String>()
        try {
            pathsCache = listPaths()
            for (p in pathsCache) {
                try { map[p] = files.read(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* unreadable or too large: skipped */ }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(runningChecks = false) }
            throw e
        } catch (e: Exception) {
            errorSink("The project files could not be read", e)
            _state.update { it.copy(runningChecks = false, message = "The project files could not be read: ${e.message.orEmpty()}") }
            return
        }
        val result = try {
            withContext(Dispatchers.Default) { StaticDiagnostics().analyze(map) }
        } catch (e: CancellationException) {
            _state.update { it.copy(runningChecks = false) }
            throw e
        } catch (e: Throwable) {
            if (!AiFailures.isRecoverable(e)) throw e
            errorSink("The code checks failed", e)
            _state.update { it.copy(runningChecks = false, message = "The code checks could not be completed (${e.javaClass.simpleName}).") }
            return
        }
        fixes.offer(result.fixes)
        _state.update { it.copy(runningChecks = false, checksRan = true, staticFindings = result.findings, filesChecked = result.filesChecked) }
    }

    /** Fixes offered for one issue (pending only). */
    fun fixesFor(issue: BuildIssue): List<FixEntry> = fixes.entries.value.filter { it.proposal.issueId == issue.id && it.status == FixStatus.PENDING }

    /** Where to open the editor for [issue], or null when the issue is not tied to a project file. */
    fun targetFor(issue: BuildIssue): NavigationTarget? = issue.projectPath?.let { NavigationTarget(it, issue.line, issue.column) }

    fun setIssueFilter(f: IssueFilter) = _state.update { it.copy(issueFilter = f) }
    fun setLogFilter(f: LogFilter) = _state.update { it.copy(logFilter = f) }
    fun toggleLevel(level: LogLevel) = _state.update { it.copy(logFilter = it.logFilter.toggleLevel(level)) }
    fun setQuery(q: String) = _state.update { it.copy(logFilter = it.logFilter.copy(query = q)) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    /** Shows [text] in the screen's message bar (used by the screen's last-resort error handler). */
    fun postMessage(text: String) = _state.update { it.copy(message = text) }

    /** Text to copy: the visible (filtered) log lines. */
    fun copyText(): String = LogExport.toText(state.value.visibleLog)

    fun exportText(format: LogExportFormat): String = LogExport.render(state.value.visibleLog, format, projectName, buildId)

    suspend fun applyFix(id: String): FixResult {
        val r = fixes.apply(id)
        afterFileChange(r is FixResult.Applied)
        _state.update { it.copy(message = describe(r)) }
        return r
    }

    suspend fun undoFix(): UndoResult {
        val r = fixes.undo()
        afterFileChange(r is UndoResult.Undone)
        _state.update { it.copy(message = describe(r)) }
        return r
    }

    /** Phase 23: files were changed (or restored) by the repair engine; static findings are out of date. */
    suspend fun filesChangedExternally() { afterFileChange(true) }

    /** Files changed: static findings are out of date, so run them again (only if they had been run). */
    private suspend fun afterFileChange(changed: Boolean) {
        if (changed && state.value.checksRan) runStaticChecks()
    }

    private fun describe(r: FixResult): String = when (r) {
        is FixResult.Applied -> "Fix applied to ${r.entry.proposal.path}. You can undo it."
        is FixResult.Stale -> "${r.path} changed after this suggestion was made. Nothing was changed; run the checks again."
        is FixResult.Invalid -> r.reason
        is FixResult.Failed -> r.reason
        FixResult.NotFound -> "That suggestion is no longer in the list."
    }

    private fun describe(r: UndoResult): String = when (r) {
        is UndoResult.Undone -> "Undone: ${r.proposal.title}"
        is UndoResult.Conflict -> "${r.path} was edited after the fix, so undo would lose those edits. Nothing was changed."
        is UndoResult.Failed -> r.reason
        UndoResult.NothingToUndo -> "Nothing to undo."
    }

    // ---- AI (prepared, not connected in Phase 8) ----------------------------------------------------------------

    suspend fun aiAvailability(): AiAvailability = ai.availability()

    /** Builds the request WITHOUT sending it, so the UI can show what would leave the device and ask first. */
    suspend fun buildAiRequest(task: AiTask, includeCode: Boolean): AiDebugRequest {
        val s = state.value
        return AiRequestBuilder.build(
            task, projectName, buildId, AnalysisResult(s.buildIssues, s.buildFailed, s.omittedWarnings), s.issues, s.entries,
            if (includeCode) files else null,
            diagnosis = s.diagnosis,
        )
    }

    // ---- Phase 22: diagnosis report and follow-up questions ---------------------------------------------------------

    /**
     * The eight-section report for [focusIssueId] (default: the problem to fix first), or null when the log holds nothing
     * to diagnose. If an AI diagnosis exists for that problem it is merged in, with its claims checked (see
     * [DiagnosisReports.withAi]); otherwise the report is the local, rule-based one.
     */
    fun diagnosisReport(focusIssueId: String? = null): DiagnosisReport? {
        val d = state.value.diagnosis
        val focus = focusIssueId?.let { d.find(it) } ?: d.primary ?: d.issues.firstOrNull() ?: return null
        val pending = fixes.entries.value.filter { it.status == FixStatus.PENDING }.map { it.proposal }
        val local = DiagnosisReports.local(d, focus, pending) ?: return null
        val a = lastAnswer
        val aboutFocus = a != null && a.request.issues.firstOrNull()?.id?.let { it == focus.id || it in focus.mergedIssueIds } == true
        if (a == null || !aboutFocus) return local
        return DiagnosisReports.withAi(local, a.response, focus.confidence, pathsCache, a.offered, a.rejected)
    }

    /**
     * Answers a follow-up [question] about the diagnosis. It sends the local diagnosis, the earlier answer and the SAME log
     * excerpt and code snippets the user already confirmed for the diagnosis; without an earlier confirmed request only a
     * redacted log excerpt goes out, never source code. Never throws for a failure; only cancellation is rethrown.
     */
    suspend fun askFollowUp(question: String, focusIssueId: String?, history: List<FollowUpExchange>): FollowUpOutcome {
        val s = state.value
        if (s.diagnosis.isEmpty) return FollowUpOutcome.Unavailable("There is no diagnosed error to ask about. Open a build with errors first.")
        val focus = focusIssueId?.let { s.diagnosis.find(it) } ?: s.diagnosis.primary
        val previous = lastAnswer
        val request = try {
            val base = previous?.request ?: AiRequestBuilder.build(
                AiTask.DIAGNOSE, projectName, buildId, AnalysisResult(s.buildIssues, s.buildFailed, s.omittedWarnings), s.issues, s.entries, null, s.diagnosis,
            )
            AiFollowUpRequest(
                projectName = projectName,
                question = question,
                diagnosisContext = DiagnosisText.forFollowUp(s.diagnosis, focus),
                previousAnswer = previous?.let { FollowUpPrompt.summarize(it.response) }.orEmpty(),
                history = FollowUpPrompt.boundHistory(history).map { FollowUpExchange(it.question, AiRequestBuilder.safeRedact(it.answer, FollowUpPrompt.MAX_HISTORY_ANSWER_CHARS)) },
                logExcerpt = base.logExcerpt,
                files = base.files,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!AiFailures.isRecoverable(e)) throw e
            return AiFailures.describe(e).let { (m, r) -> FollowUpOutcome.Failed(m, r, e) }
        }
        val result = try {
            ai.followUp(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!AiFailures.isRecoverable(e)) throw e
            return AiFailures.describe(e).let { (m, r) -> FollowUpOutcome.Failed(m, r, e) }
        }
        return when (result) {
            is FollowUpResult.Unavailable -> FollowUpOutcome.Unavailable(result.reason)
            is FollowUpResult.Failed -> FollowUpOutcome.Failed(result.message, result.retryable, result.cause)
            is FollowUpResult.Success -> FollowUpOutcome.Answer(result.answer, ConfidenceCalibrator.calibrate(result.answer.confidence, focus?.confidence))
        }
    }

    /** True when the follow-up request would carry source code snippets (the screen tells the user). */
    fun followUpIncludesCode(): Boolean = lastAnswer?.request?.containsSourceCode == true

    /**
     * Sends [request] to the provider and offers any valid patches as fixes (never applied automatically: they only
     * join the list of suggestions, which the user previews and applies one by one).
     *
     * Never throws for a failed request: provider problems, unexpected exceptions and unusable answers all come back as
     * [AiOutcome.Failed]. Only cancellation is rethrown, so the caller's coroutine can stop properly.
     */
    suspend fun askAi(request: AiDebugRequest): AiOutcome {
        val result = try {
            ai.analyze(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!AiFailures.isRecoverable(e)) throw e
            return AiFailures.outcome(e)
        }
        return when (result) {
            is AiResult.Unavailable -> AiOutcome.Unavailable(result.reason)
            is AiResult.Failed -> AiOutcome.Failed(result.message, result.retryable, result.cause)
            is AiResult.Success -> try {
                val conv = AiPatchConverter.convert(result.response, files, request.issues.firstOrNull()?.id)
                val ok = conv.filterIsInstance<PatchConversion.Ok>().map { it.proposal }
                val rejected = conv.filterIsInstance<PatchConversion.Rejected>()
                fixes.offer(ok)
                lastAnswer = StoredAnswer(result.response, request, ok, rejected.size)
                AiOutcome.Answer(result.response, rejected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!AiFailures.isRecoverable(e)) throw e
                AiOutcome.Failed("The AI answered, but its suggested changes could not be checked (${e.javaClass.simpleName}). Nothing was changed. Try again.", true, e)
            }
        }
    }
}

sealed interface AiOutcome {
    data class Answer(val response: AiDebugResponse, val rejectedPatches: List<PatchConversion.Rejected>) : AiOutcome
    data class Unavailable(val reason: String) : AiOutcome
    data class Failed(val message: String, val retryable: Boolean, val cause: Throwable? = null) : AiOutcome
}
