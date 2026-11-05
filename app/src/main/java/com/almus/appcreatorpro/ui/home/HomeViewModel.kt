package com.almus.appcreatorpro.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.StorageInfo
import com.almus.appcreatorpro.domain.repository.BuildRepository
import com.almus.appcreatorpro.domain.repository.GitHubRepository
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.StorageInfoProvider
import com.almus.appcreatorpro.service.build.BuildService
import com.almus.appcreatorpro.ui.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeData(
    val recentProjects: List<Project>,
    val projectCount: Int,
    val recentBuilds: List<BuildRecord>,
    val gitHub: GitHubConnection,
    val storage: StorageInfo,
    val cloudBuildName: String,
    val cloudBuildAvailable: Boolean,
)

class HomeViewModel(
    private val projectRepository: ProjectRepository,
    private val buildRepository: BuildRepository,
    private val gitHubRepository: GitHubRepository,
    private val storageInfoProvider: StorageInfoProvider,
    private val buildService: BuildService,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<HomeData>>(UiState.Loading)
    val state: StateFlow<UiState<HomeData>> = _state.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        _state.value = UiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val builds = buildRepository.getBuildHistory()
                val gitHub = gitHubRepository.getConnection()
                projectRepository.observeProjects().collect { projects ->
                    _state.value = UiState.Success(
                        HomeData(
                            recentProjects = projects.take(RECENT_COUNT),
                            projectCount = projects.size,
                            recentBuilds = builds.take(RECENT_COUNT),
                            gitHub = gitHub,
                            storage = storageInfoProvider.getStorageInfo(),
                            cloudBuildName = buildService.displayName,
                            cloudBuildAvailable = buildService.isAvailable,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState.Error(e.toUserMessage())
            }
        }
    }

    /** Asks the build service to build the most recently updated project. */
    fun startCloudBuild() {
        viewModelScope.launch {
            try {
                val target = projectRepository.getProjects().firstOrNull()
                if (target == null) {
                    _message.value = "Create a project first, then start a cloud build."
                    return@launch
                }
                buildService.requestBuild(target).fold(
                    onSuccess = { _message.value = "Build ${it.id} queued." },
                    onFailure = { _message.value = it.toUserMessage() },
                )
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

    private companion object {
        const val RECENT_COUNT = 3
    }
}
