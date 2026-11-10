package com.almus.appcreatorpro.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.agent.AgentProjectLauncher
import com.almus.appcreatorpro.agent.AgentRunner
import com.almus.appcreatorpro.ai.ApplyOutcome
import com.almus.appcreatorpro.ai.UndoOutcome
import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.service.ai.AiHub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AgentViewModel(private val projectId: String, private val hub: AiHub) : ViewModel() {
    private val _runner = MutableStateFlow<AgentRunner?>(null)
    val runner: StateFlow<AgentRunner?> = _runner.asStateFlow()
    private val _availability = MutableStateFlow<AiAvailability?>(null)
    val availability: StateFlow<AiAvailability?> = _availability.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            _runner.value = hub.agent(projectId)
            refreshAvailability()
        }
    }

    fun refreshAvailability() { viewModelScope.launch { _availability.value = hub.backend.availability() } }
    fun consumeMessage() { _message.value = null }

    fun apply(selected: Set<String>) {
        val r = _runner.value ?: return
        viewModelScope.launch {
            _message.value = when (val o = r.applyToProject(selected)) {
                is ApplyOutcome.Applied -> "Applied ${o.record.files.size} file(s) to the project. You can roll it back."
                is ApplyOutcome.Stale -> "Nothing was written: these files changed after the agent worked on them: ${o.paths.joinToString()}. Discard the run and start again."
                is ApplyOutcome.Invalid -> o.reason
                is ApplyOutcome.Failed -> "Applying failed: ${o.reason}"
                ApplyOutcome.NotFound -> "That result is no longer available."
            }
        }
    }

    fun rollback(force: Boolean) {
        val r = _runner.value ?: return
        viewModelScope.launch {
            _message.value = when (val o = r.rollbackApplied(force)) {
                is UndoOutcome.Undone -> "Rolled back. The project is as it was before the agent's files were applied."
                is UndoOutcome.Conflict -> "Not rolled back: these files were edited since: ${o.paths.joinToString()}."
                is UndoOutcome.Failed -> "Rollback failed: ${o.reason}"
                UndoOutcome.NotFound -> "There is nothing to roll back."
            }
        }
    }
}

class AgentNewProjectViewModel(private val hub: AiHub) : ViewModel() {
    val launcher: AgentProjectLauncher = hub.newAgentLauncher()
    val availability = MutableStateFlow<AiAvailability?>(null)

    init { refreshAvailability() }

    fun refreshAvailability() { viewModelScope.launch { availability.value = hub.backend.availability() } }

    override fun onCleared() { launcher.cancel() }
}
