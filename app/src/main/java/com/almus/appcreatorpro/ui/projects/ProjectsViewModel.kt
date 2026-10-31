package com.almus.appcreatorpro.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Project>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Project>>> = _state.asStateFlow()

    /** One-shot message for a snackbar; the UI calls [consumeMessage] after showing it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Problems found while loading saved projects (damaged folders); shown as a banner. */
    val loadIssues: StateFlow<List<String>> = projectRepository.observeLoadIssues()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var observeJob: Job? = null

    init {
        load()
    }

    fun load() {
        observeJob?.cancel()
        _state.value = UiState.Loading
        observeJob = viewModelScope.launch {
            try {
                // Initial read so the loading state is shown while the repository works.
                projectRepository.getProjects()
                projectRepository.observeProjects().collect { projects ->
                    _state.value = UiState.Success(projects)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.toUserMessage())
            }
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            try {
                projectRepository.deleteProject(project.id)
                _message.value = "Deleted \"${project.name}\"."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.toUserMessage()
            }
        }
    }

    fun renameProject(project: Project, newName: String) {
        viewModelScope.launch {
            try {
                val updated = projectRepository.renameProject(project.id, newName)
                _message.value = "Renamed to \"${updated.name}\"."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.toUserMessage()
            }
        }
    }

    fun onProjectCreated(project: Project) {
        _message.value = "Created \"${project.name}\"."
    }

    fun consumeMessage() {
        _message.value = null
    }
}
