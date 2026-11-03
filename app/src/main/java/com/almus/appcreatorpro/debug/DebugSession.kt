package com.almus.appcreatorpro.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
) {
    val fixes = FixManager(files)

    private val _state = MutableStateFlow(DebugState())
    val state: StateFlow<DebugState> = _state.asStateFlow()

    private var pathsCache: List<String> = emptyList()

    /** Reads and analyses the build log. Safe to call again (refresh). */
    suspend fun loadBuildLog() {
        if (buildId == null) {
            _state.update { it.copy(logLoaded = true, logNote = "No build is selected. Use the code checks, or open a build and choose \"Analyze errors\".") }
            return
        }
        _state.update { it.copy(loadingLog = true, message = null) }
        val raw = try { loadLog() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { RawBuildLog(null, "The log could not be loaded: ${e.message.orEmpty()}") }
        val text = raw.text
        if (text.isNullOrBlank()) {
            _state.update { it.copy(loadingLog = false, logLoaded = true, logNote = raw.note ?: "This build has no log yet.", buildIssues = emptyList(), entries = emptyList()) }
            return
        }
        pathsCache = try { listPaths() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val result = BuildErrorAnalyzer(ProjectPathResolver(pathsCache)).analyze(text)
        val entries = LogParser.parse(text)
        _state.update {
            it.copy(
                loadingLog = false, logLoaded = true, logNote = raw.note, buildFailed = result.buildFailed,
                buildIssues = result.issues, omittedWarnings = result.omittedWarnings, entries = entries,
            )
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
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            _state.update { it.copy(runningChecks = false, message = "The project files could not be read: ${e.message.orEmpty()}") }
            return
        }
        val result = StaticDiagnostics().analyze(map)
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
        )
    }

    /** Sends [request] to the provider and offers any valid patches as fixes (never applied automatically). */
    suspend fun askAi(request: AiDebugRequest): AiOutcome {
        return when (val r = ai.analyze(request)) {
            is AiResult.Unavailable -> AiOutcome.Unavailable(r.reason)
            is AiResult.Failed -> AiOutcome.Failed(r.message, r.retryable)
            is AiResult.Success -> {
                val conv = AiPatchConverter.convert(r.response, files, request.issues.firstOrNull()?.id)
                fixes.offer(conv.filterIsInstance<PatchConversion.Ok>().map { it.proposal })
                AiOutcome.Answer(r.response, conv.filterIsInstance<PatchConversion.Rejected>())
            }
        }
    }
}

sealed interface AiOutcome {
    data class Answer(val response: AiDebugResponse, val rejectedPatches: List<PatchConversion.Rejected>) : AiOutcome
    data class Unavailable(val reason: String) : AiOutcome
    data class Failed(val message: String, val retryable: Boolean) : AiOutcome
}
