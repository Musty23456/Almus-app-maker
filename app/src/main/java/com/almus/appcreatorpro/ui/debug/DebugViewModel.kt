package com.almus.appcreatorpro.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.debug.AiDebugRequest
import com.almus.appcreatorpro.debug.AiOutcome
import com.almus.appcreatorpro.debug.AiTask
import com.almus.appcreatorpro.debug.DebugSession
import com.almus.appcreatorpro.debug.DebugState
import com.almus.appcreatorpro.debug.FixEntry
import com.almus.appcreatorpro.debug.FixPreview
import com.almus.appcreatorpro.debug.LogExportFormat
import com.almus.appcreatorpro.service.debug.DebugHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A fix preview the user is looking at (before / after). */
data class FixPreviewUi(val entry: FixEntry, val preview: FixPreview?, val unavailableReason: String?)

/** What the AI section shows. Nothing is sent until the user confirms. */
sealed interface AiUi {
    data object Idle : AiUi
    data class Confirm(val request: AiDebugRequest) : AiUi
    data object Working : AiUi
    data class Done(val outcome: AiOutcome) : AiUi
}

/** Thin wrapper: the logic is in the pure [DebugSession]. */
class DebugViewModel(
    private val projectId: String,
    private val buildId: String?,
    private val hub: DebugHub,
) : ViewModel() {

    private val _session = MutableStateFlow<DebugSession?>(null)
    val session: StateFlow<DebugSession?> = _session.asStateFlow()

    private val _preview = MutableStateFlow<FixPreviewUi?>(null)
    val preview: StateFlow<FixPreviewUi?> = _preview.asStateFlow()

    private val _ai = MutableStateFlow<AiUi>(AiUi.Idle)
    val ai: StateFlow<AiUi> = _ai.asStateFlow()

    private val _aiNote = MutableStateFlow<String?>(null)
    val aiNote: StateFlow<String?> = _aiNote.asStateFlow()

    private val _exportMessage = MutableStateFlow<String?>(null)
    val exportMessage: StateFlow<String?> = _exportMessage.asStateFlow()

    init {
        viewModelScope.launch {
            val s = hub.session(projectId, buildId, hub.projectName(projectId))
            _session.value = s
            _aiNote.value = (s.aiAvailability() as? AiAvailability.NotReady)?.reason
            // Reuse what is already there when the screen is opened again; only the first visit does the work.
            val st = s.state.value
            if (!st.logLoaded) s.loadBuildLog()
            if (!st.checksRan) s.runStaticChecks()
        }
    }

    fun refresh() {
        val s = _session.value ?: return
        viewModelScope.launch {
            s.loadBuildLog()
            s.runStaticChecks()
        }
    }

    fun runChecks() {
        val s = _session.value ?: return
        viewModelScope.launch { s.runStaticChecks() }
    }

    fun showPreview(id: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
            val e = s.fixes.entry(id) ?: return@launch
            val p = s.fixes.preview(id)
            _preview.value = FixPreviewUi(e, p, if (p == null) "The file changed after this suggestion was made. Run the checks again." else null)
        }
    }

    fun closePreview() { _preview.value = null }

    fun apply(id: String) {
        val s = _session.value ?: return
        viewModelScope.launch {
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
        viewModelScope.launch { s.undoFix() }
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

    // ---- AI (prepared only)
    fun prepareAi(task: AiTask, includeCode: Boolean) {
        val s = _session.value ?: return
        viewModelScope.launch { _ai.value = AiUi.Confirm(s.buildAiRequest(task, includeCode)) }
    }

    fun cancelAi() { _ai.value = AiUi.Idle }

    fun sendAi(request: AiDebugRequest) {
        val s = _session.value ?: return
        _ai.value = AiUi.Working
        viewModelScope.launch { _ai.value = AiUi.Done(s.askAi(request)) }
    }
}
