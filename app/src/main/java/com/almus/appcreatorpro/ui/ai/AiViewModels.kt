package com.almus.appcreatorpro.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.ai.AiBackend
import com.almus.appcreatorpro.ai.AiErrorKind
import com.almus.appcreatorpro.ai.AiException
import com.almus.appcreatorpro.ai.AiProjectGenerator
import com.almus.appcreatorpro.ai.AiProjectSession
import com.almus.appcreatorpro.ai.AiSettingsStore
import com.almus.appcreatorpro.ai.ApplyOutcome
import com.almus.appcreatorpro.ai.ProjectPlan
import com.almus.appcreatorpro.ai.UndoOutcome
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.service.ai.AiHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AiSettingsUi(
    val url: String = "",
    val hasAccessCode: Boolean = false,
    val testing: Boolean = false,
    val models: List<String> = emptyList(),
    val defaultModel: String? = null,
    val selectedModel: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class AiSettingsViewModel(private val settings: AiSettingsStore, private val backend: AiBackend) : ViewModel() {
    private val _state = MutableStateFlow(
        AiSettingsUi(url = settings.baseUrl().orEmpty(), hasAccessCode = settings.hasAccessCode(), selectedModel = settings.selectedModel()),
    )
    val state: StateFlow<AiSettingsUi> = _state.asStateFlow()

    /** [code] blank = keep the stored access code. The code is never shown again after saving. */
    fun save(url: String, code: String) {
        when (val r = settings.save(url, code)) {
            AiSettingsStore.SaveResult.Saved ->
                _state.value = _state.value.copy(url = settings.baseUrl().orEmpty(), hasAccessCode = settings.hasAccessCode(), message = "Saved. Use \"Test connection\" to check it.", messageIsError = false)
            is AiSettingsStore.SaveResult.Invalid -> _state.value = _state.value.copy(message = r.reason, messageIsError = true)
        }
    }

    fun testConnection() {
        if (_state.value.testing) return
        _state.value = _state.value.copy(testing = true, message = null)
        viewModelScope.launch {
            try {
                val list = backend.models()
                _state.value = _state.value.copy(
                    testing = false, models = list.models.map { it.id }, defaultModel = list.default,
                    message = "Connected. ${list.models.size} model(s) available.", messageIsError = false,
                )
            } catch (e: AiException) {
                _state.value = _state.value.copy(testing = false, message = e.message, messageIsError = e.kind != AiErrorKind.CANCELLED)
            }
        }
    }

    fun selectModel(id: String?) {
        settings.selectModel(id)
        _state.value = _state.value.copy(selectedModel = settings.selectedModel())
    }

    fun disconnect() {
        settings.clear()
        _state.value = AiSettingsUi(message = "Disconnected. The address and the access code were removed from this phone.")
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}

class AiAssistantViewModel(private val projectId: String, private val hub: AiHub) : ViewModel() {
    private val _session = MutableStateFlow<AiProjectSession?>(null)
    val session: StateFlow<AiProjectSession?> = _session.asStateFlow()
    private val _paths = MutableStateFlow<List<String>>(emptyList())
    val paths: StateFlow<List<String>> = _paths.asStateFlow()
    private val _availability = MutableStateFlow<AiAvailability?>(null)
    val availability: StateFlow<AiAvailability?> = _availability.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val s = hub.session(projectId)
            s.init()
            _session.value = s
            refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _availability.value = hub.backend.availability()
            _paths.value = try { _session.value?.selectablePaths().orEmpty() } catch (e: Exception) { emptyList() }
        }
    }

    fun clearChat() { viewModelScope.launch { _session.value?.chat?.clear() } }

    fun apply(changeId: String, selected: Set<String>?) {
        val s = _session.value ?: return
        viewModelScope.launch {
            _message.value = when (val r = s.changes.apply(changeId, selected)) {
                is ApplyOutcome.Applied -> "Applied to ${r.record.files.size} file(s). You can undo it under History."
                is ApplyOutcome.Stale -> "These files changed after the suggestion was made, so nothing was changed: ${r.paths.joinToString()}. Ask again."
                is ApplyOutcome.Invalid -> r.reason
                is ApplyOutcome.Failed -> r.reason
                ApplyOutcome.NotFound -> "That change is no longer in the list."
            }
            refresh()
        }
    }

    fun undo(recordId: String, force: Boolean) {
        val s = _session.value ?: return
        viewModelScope.launch {
            _message.value = when (val r = s.changes.undo(recordId, force)) {
                is UndoOutcome.Undone -> "Restored: ${r.record.title}"
                is UndoOutcome.Conflict -> "Edited after the change: ${r.paths.joinToString()}. Nothing was changed. Use \"Restore anyway\" to overwrite your later edits."
                is UndoOutcome.Failed -> r.reason
                UndoOutcome.NotFound -> "That change was already undone."
            }
            refresh()
        }
    }

    /** "Apply to file" on a chat answer: the code becomes a reviewable change, shown under Changes. */
    fun changeFromChat(turnId: String) {
        val s = _session.value ?: return
        val turn = s.chat.state.value.turns.firstOrNull { it.id == turnId } ?: return
        viewModelScope.launch {
            val r = com.almus.appcreatorpro.ai.ChatApply.changeSet(turn, s.files, System.currentTimeMillis())
            val set = r?.changeSet
            if (set != null) {
                s.changes.offer(set)
                _message.value = "Added to Changes. Review it there before applying."
            } else {
                _message.value = r?.rejected?.firstOrNull()?.reason ?: "No code block with a known file could be turned into a change. Attach the file you want to change."
            }
        }
    }

    fun dismissMessage() { _message.value = null }
}

class AiGeneratorViewModel(private val hub: AiHub) : ViewModel() {
    val generator: AiProjectGenerator get() = hub.generator
    val availability = MutableStateFlow<AiAvailability?>(null)

    init { viewModelScope.launch { availability.value = hub.backend.availability() } }

    fun refreshAvailability() { viewModelScope.launch { availability.value = hub.backend.availability() } }
    fun updatePlan(plan: ProjectPlan) = generator.updatePlan(plan)
}
