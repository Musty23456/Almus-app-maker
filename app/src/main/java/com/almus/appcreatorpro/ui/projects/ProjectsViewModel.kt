package com.almus.appcreatorpro.ui.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.NoRecentProjects
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.RecentProjectsTracker
import com.almus.appcreatorpro.domain.transfer.ProjectListFilter
import com.almus.appcreatorpro.domain.transfer.ProjectSort
import com.almus.appcreatorpro.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
    /** Phase 5: remembers when projects were opened. Optional so older callers keep working. */
    private val recents: RecentProjectsTracker = NoRecentProjects,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Project>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Project>>> = _state.asStateFlow()

    /** One-shot message for a snackbar; the UI calls [consumeMessage] after showing it. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Problems found while loading saved projects (damaged folders); shown as a banner. */
    val loadIssues: StateFlow<List<String>> = projectRepository.observeLoadIssues()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Phase 5: search, sorting and recently opened -------------------------------------------------------
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(ProjectSort.RECENTLY_UPDATED)
    val sort: StateFlow<ProjectSort> = _sort.asStateFlow()

    /** [state]'s projects after applying the search text and the chosen sort order. */
    val visibleProjects: StateFlow<List<Project>> = combine(_state, _query, _sort, recents.opened) { current, q, order, opened ->
        val all = (current as? UiState.Success)?.data.orEmpty()
        ProjectListFilter.apply(all, q, order, opened)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The last few projects opened in the editor (hidden by the screen while searching). */
    val recentProjects: StateFlow<List<Project>> = combine(_state, recents.opened) { current, opened ->
        ProjectListFilter.recent((current as? UiState.Success)?.data.orEmpty(), opened)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onSortChange(value: ProjectSort) {
        _sort.value = value
    }

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
                recents.forget(project.id)
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
