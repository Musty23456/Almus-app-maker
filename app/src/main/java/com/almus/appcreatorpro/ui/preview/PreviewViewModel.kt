package com.almus.appcreatorpro.ui.preview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.ai.ApplyOutcome
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.preview.PreviewKind
import com.almus.appcreatorpro.preview.PreviewSession
import com.almus.appcreatorpro.preview.UiGenResult
import com.almus.appcreatorpro.service.preview.PreviewHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PreviewViewModel(private val projectId: String, private val hub: PreviewHub) : ViewModel() {
    private val _session = MutableStateFlow<PreviewSession?>(null)
    val session: StateFlow<PreviewSession?> = _session.asStateFlow()
    private val _availability = MutableStateFlow<AiAvailability?>(null)
    val availability: StateFlow<AiAvailability?> = _availability.asStateFlow()
    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            val s = hub.session(projectId)
            _session.value = s
            s.open()
            s.startWatching()
            refreshAvailability()
        }
    }

    fun refreshAvailability() { viewModelScope.launch { _availability.value = hub.aiAvailability() } }
    fun consumeMessage() { _message.value = null }

    fun generate(kind: PreviewKind, request: String, refine: Boolean) {
        if (_aiBusy.value) return
        _aiBusy.value = true
        viewModelScope.launch {
            try {
                when (val r = hub.generate(projectId, kind, request, refine)) {
                    is UiGenResult.Ok -> _message.value = "Draft shown in the preview. It is not saved to your project yet."
                    is UiGenResult.Failed -> _message.value = r.message
                }
            } finally { _aiBusy.value = false }
        }
    }

    fun saveDraft() {
        viewModelScope.launch {
            _message.value = when (val o = hub.saveDraft(projectId)) {
                is ApplyOutcome.Applied -> "Saved ${o.record.files.size} file(s) to the project. You can undo this in the AI assistant's history."
                is ApplyOutcome.Stale -> "Nothing was saved: these files changed meanwhile: ${o.paths.joinToString()}."
                is ApplyOutcome.Invalid -> o.reason
                is ApplyOutcome.Failed -> "Saving failed: ${o.reason}"
                ApplyOutcome.NotFound -> "The draft is no longer available."
            }
        }
    }

    fun discardDraft() { _session.value?.discardDraft() }

    override fun onCleared() { hub.release(projectId) }
}
