package com.almus.appcreatorpro.ui.debug

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.debug.AiDebugController
import com.almus.appcreatorpro.debug.AiDebugRequest
import com.almus.appcreatorpro.debug.AiTask
import com.almus.appcreatorpro.debug.AiUi
import com.almus.appcreatorpro.debug.DebugSession
import com.almus.appcreatorpro.debug.DebugState
import com.almus.appcreatorpro.debug.FixEntry
import com.almus.appcreatorpro.debug.FixPreview
import com.almus.appcreatorpro.debug.FixStatus
import com.almus.appcreatorpro.debug.FollowUpController
import com.almus.appcreatorpro.debug.FollowUpState
import com.almus.appcreatorpro.debug.LogExportFormat
import com.almus.appcreatorpro.repair.RepairApplyOutcome
import com.almus.appcreatorpro.repair.RepairPreview
import com.almus.appcreatorpro.repair.RepairRollbackOutcome
import com.almus.appcreatorpro.repair.RepairSession
import com.almus.appcreatorpro.service.debug.DebugHub
import com.almus.appcreatorpro.service.repair.RepairHub
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A fix preview the user is looking at (before / after). */
data class FixPreviewUi(val entry: FixEntry, val preview: FixPreview?, val unavailableReason: String?)

/** Thin wrapper: the logic is in the pure [DebugSession]. */
class DebugViewModel(
    private val projectId: String,
    private val buildId: String?,
    private val hub: DebugHub,
    /** Phase 23: the code repair engine. Null (for example in tests) means the Repair tab says it is not available. */
    private val repairHub: RepairHub? = null,
) : ViewModel() {

    private val _session = MutableStateFlow<DebugSession?>(null)
    val session: StateFlow<DebugSession?> = _session.asStateFlow()

    private val _preview = MutableStateFlow<FixPreviewUi?>(null)
    val preview: StateFlow<FixPreviewUi?> = _preview.asStateFlow()

    /**
     * Last line of defence: an exception that nothing below caught would otherwise end the whole app process (an
     * uncaught exception in viewModelScope reaches Android's default handler). It is logged with its stack trace and
     * shown as a message; it is not hidden. Cancellation never reaches this handler.
     */
    private val errorHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "Unhandled error in the debug screen", t)
        _session.value?.postMessage("Something went wrong (${t.javaClass.simpleName}). Nothing in your project was changed. Try again.")
    }

    /** One AI request at a time, cancellable, with a deadline; see [AiDebugController]. */
    private val aiController = AiDebugController(
        scope = viewModelScope,
        sessionProvider = { _session.value },
        errorSink = { message, t -> Log.e(TAG, message, t) },
    )
    val ai: StateFlow<AiUi> = aiController.state

    /** Phase 22: follow-up questions about the diagnosed error; same rules as [aiController]. */
    private val followUpController = FollowUpController(
        scope = viewModelScope,
        sessionProvider = { _session.value },
        errorSink = { message, t -> Log.e(TAG, message, t) },
    )
    val followUp: StateFlow<FollowUpState> = followUpController.state

    // ---- Phase 23: code repair
    private val _repair = MutableStateFlow<RepairSession?>(null)
    val repair: StateFlow<RepairSession?> = _repair.asStateFlow()

    /** The patch the user is looking at: original, modified text and the differences. */
    private val _repairPreview = MutableStateFlow<RepairPreview?>(null)
    val repairPreview: StateFlow<RepairPreview?> = _repairPreview.asStateFlow()

    /** Set when a rollback was refused because files were edited after the repair: (apply id, files). The screen asks the user. */
    private val _rollbackConflict = MutableStateFlow<Pair<String, List<String>>?>(null)
    val rollbackConflict: StateFlow<Pair<String, List<String>>?> = _rollbackConflict.asStateFlow()

    private val _aiNote = MutableStateFlow<String?>(null)
    val aiNote: StateFlow<String?> = _aiNote.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    init {
        viewModelScope.launch(errorHandler) {
            val s = hub.session(projectId, buildId, hub.projectName(projectId))
            _session.value = s
            _aiNote.value = try {
                (s.aiAvailability() as? AiAvailability.NotReady)?.reason
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI availability could not be checked", e)
                "The AI assistant could not be checked (${e.javaClass.simpleName}). Open AI settings to review it."
            }
            // Reuse what is already there when the screen is opened again; only the first visit does the work.
            val st = s.state.value
            if (!st.logLoaded) s.loadBuildLog()
            if (!st.checksRan) s.runStaticChecks()
        }
    }

    init {
        viewModelScope.launch(errorHandler) {
            val rh = repairHub ?: return@launch
            _repair.value = try {
                rh.session(projectId)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "The repair engine could not be opened", e)
                null
            }
        }
    }

    fun refresh() {
        val s = _session.value ?: return
        viewModelScope.launch(errorHandler) {
            s.loadBuildLog()
            s.runStaticChecks()
        }
    }

    fun runChecks() {
        val s = _session.value ?: return
        viewModelScope.launch(errorHandler) { s.runStaticChecks() }
    }

    fun showPreview(id: String) {
        val s = _session.value ?: return
        viewModelScope.launch(errorHandler) {
            val e = s.fixes.entry(id) ?: return@launch
            val p = s.fixes.preview(id)
            _preview.value = FixPreviewUi(e, p, if (p == null) "The file changed after this suggestion was made. Run the checks again." else null)
        }
    }

    fun closePreview() { _preview.value = null }

    fun apply(id: String) {
        val s = _session.value ?: return
        viewModelScope.launch(errorHandler) {
            s.applyFix(id)
            _preview.value = null
        }
    }

    fun reject(id: String) {
        _session.value?.fixes?.reject(id)
        _preview.value = null
    }

    fun restore(id: String) { _session.value?.fixes?.restore(id) }

    fun undo() {
        val s = _session.value ?: return
        viewModelScope.launch(errorHandler) { s.undoFix() }
    }

    fun dismissMessage() { _session.value?.dismissMessage() }

    // ---- log viewer
    fun toggleLevel(l: com.almus.appcreatorpro.debug.LogLevel) { _session.value?.toggleLevel(l) }
    fun setQuery(q: String) { _session.value?.setQuery(q) }
    fun setIssueFilter(f: com.almus.appcreatorpro.debug.IssueFilter) { _session.value?.setIssueFilter(f) }
    fun setRegex(on: Boolean) { _session.value?.let { s -> s.setLogFilter(s.state.value.logFilter.copy(useRegex = on)) } }
    fun setCaseSensitive(on: Boolean) { _session.value?.let { s -> s.setLogFilter(s.state.value.logFilter.copy(caseSensitive = on)) } }
    fun copyText(): String = _session.value?.copyText().orEmpty()
    fun exportText(format: LogExportFormat): String = _session.value?.exportText(format).orEmpty()
    fun exportFileName(format: LogExportFormat): String =
        com.almus.appcreatorpro.debug.LogExport.fileName(_session.value?.projectName, format, java.time.LocalDate.now().toString())

    fun postExportMessage(text: String) { _exportMessage.value = text }
    fun consumeExportMessage() { _exportMessage.value = null }

    // ---- AI. Nothing is sent before the user confirms; one request at a time; every request can be cancelled or retried.
    fun prepareAi(task: AiTask, includeCode: Boolean) { aiController.prepare(task, includeCode) }

    /** Closes the confirmation, stops a running request, or closes a finished answer. */
    fun cancelAi() { aiController.cancel() }

    fun sendAi(request: AiDebugRequest) { aiController.send(request) }

    /** Sends the same, already confirmed request again (or rebuilds it if building it had failed). */
    fun retryAi() { aiController.retry() }

    // ---- Phase 22: follow-up questions. Sent only when the user presses Send.
    fun focusFollowUp(issueId: String?) { followUpController.focus(issueId) }

    /** Returns false when the question was empty or another one is still being answered (the text then stays in the box). */
    fun askFollowUp(question: String): Boolean = followUpController.ask(question)
    fun retryFollowUp() { followUpController.retry() }
    fun cancelFollowUp() { followUpController.cancel() }
    fun clearFollowUp() { followUpController.clear() }

    // ---- Phase 23: code repair. Nothing is written until the user accepts files and presses Apply.

    /** Builds the repair plan for the diagnosed error [issueId] (default: the one to fix first). Reads files, writes nothing. */
    fun prepareRepair(issueId: String?) {
        val s = _session.value ?: return
        val r = _repair.value
        if (r == null) { s.postMessage("The repair engine is not available for this project."); return }
        viewModelScope.launch(errorHandler) {
            val d = s.state.value.diagnosis
            val issue = issueId?.let { d.find(it) } ?: d.primary ?: d.issues.firstOrNull()
            if (issue == null) { s.postMessage("There is no diagnosed error to repair. Open a build with errors first."); return@launch }
            val pending = s.fixes.entries.value.filter { it.status == FixStatus.PENDING }.map { it.proposal }
            r.prepare(issue, pending)
        }
    }

    fun acceptRepair(path: String) { _repair.value?.accept(path) }
    fun acceptAllRepair() { _repair.value?.acceptAllValid() }
    fun clearRepairDecision(path: String) { _repair.value?.clearDecision(path) }

    fun rejectRepair(path: String) {
        val r = _repair.value ?: return
        viewModelScope.launch(errorHandler) { r.reject(path); _repairPreview.value = null }
    }

    fun rejectAllRepair() {
        val r = _repair.value ?: return
        viewModelScope.launch(errorHandler) { r.rejectAllPending() }
    }

    fun applyRepair() {
        val s = _session.value ?: return
        val r = _repair.value ?: return
        viewModelScope.launch(errorHandler) {
            val outcome = r.apply()
            if (outcome is RepairApplyOutcome.Applied) {
                s.fixes.markApplied(outcome.proposalIds.toSet())
                s.filesChangedExternally()
            }
            _repairPreview.value = null
        }
    }

    /** [force] only after the user confirmed that edits made after the repair may be lost (the current text is saved first). */
    fun rollbackRepair(applyId: String, force: Boolean = false) {
        val s = _session.value ?: return
        val r = _repair.value ?: return
        viewModelScope.launch(errorHandler) {
            when (val outcome = r.rollback(applyId, force)) {
                is RepairRollbackOutcome.RolledBack -> {
                    s.fixes.markPending(outcome.proposalIds.toSet())
                    s.filesChangedExternally()
                    _rollbackConflict.value = null
                }
                is RepairRollbackOutcome.Conflict -> _rollbackConflict.value = applyId to outcome.paths
                else -> _rollbackConflict.value = null
            }
        }
    }

    fun dismissRollbackConflict() { _rollbackConflict.value = null }
    fun showRepairPreview(path: String) { _repairPreview.value = _repair.value?.preview(path) }
    fun closeRepairPreview() { _repairPreview.value = null }
    fun discardRepair() { _repair.value?.discard(); _repairPreview.value = null }
    fun dismissRepairMessage() { _repair.value?.dismissMessage() }

    override fun onCleared() {
        aiController.cancel()
        followUpController.cancel()
        super.onCleared()
    }

    private companion object {
        const val TAG = "AiDebug"
    }
}
