package com.almus.appcreatorpro.ui.cloudbuild

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.github.CloudBuildInputValidator
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.service.build.BuildTracker
import com.almus.appcreatorpro.service.build.CloudBuildOrchestrator
import com.almus.appcreatorpro.service.build.PreflightResult
import com.almus.appcreatorpro.service.build.StartResult
import com.almus.appcreatorpro.service.build.WorkflowTemplateProvider
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloudBuildUiState(
    val loading: Boolean = true,
    val project: Project? = null,
    val connected: Boolean = false,
    /** Null until a repository has been chosen. */
    val config: CloudBuildConfig? = null,
    val dir: String = "",
    val gradle: String = CloudBuildConfig.DEFAULT_GRADLE_VERSION,
    val dirError: String? = null,
    val gradleError: String? = null,
    val checking: Boolean = false,
    val preflight: PreflightResult? = null,
    val starting: Boolean = false,
    /** Why the last "Build" tap did nothing (nothing was uploaded). */
    val rejected: List<String> = emptyList(),
    val error: String? = null,
) {
    val canStart: Boolean
        get() = connected && config != null && dirError == null && gradleError == null && !starting && !checking
}

class CloudBuildViewModel(
    private val projectId: String,
    private val projects: ProjectRepository,
    private val configs: CloudBuildConfigStore,
    private val auth: GitHubAuthManager,
    private val orchestrator: CloudBuildOrchestrator,
    private val tracker: BuildTracker,
    private val workflowTemplate: WorkflowTemplateProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(CloudBuildUiState())
    val state: StateFlow<CloudBuildUiState> = _state.asStateFlow()

    /** Set once a build has started; the screen navigates to its status page and then calls [consumeStarted]. */
    private val _startedBuildId = MutableStateFlow<String?>(null)
    val startedBuildId: StateFlow<String?> = _startedBuildId.asStateFlow()

    init {
        load()
    }

    /** Re-reads project, sign-in and saved settings (call again after returning from the repository picker). */
    fun load() {
        viewModelScope.launch {
            try {
                val project = projects.getProject(projectId)
                if (project == null) {
                    _state.value = CloudBuildUiState(loading = false, error = "This project no longer exists.")
                    return@launch
                }
                auth.ensureVerified()
                val saved = configs.get(projectId)
                val dir = saved?.projectDir?.takeIf { it.isNotBlank() } ?: CloudBuildConfig.defaultDir(project.name)
                val gradle = saved?.gradleVersion ?: CloudBuildConfig.DEFAULT_GRADLE_VERSION
                _state.value = CloudBuildUiState(
                    loading = false,
                    project = project,
                    connected = auth.state.value is GitHubAuthState.Connected,
                    config = saved?.copy(projectDir = dir, gradleVersion = gradle),
                    dir = dir,
                    gradle = gradle,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun onDirChange(value: String) {
        val v = value.trim()
        _state.update { it.copy(dir = v, dirError = CloudBuildInputValidator.validateDirectory(v), preflight = null, rejected = emptyList()) }
        persist()
    }

    fun onGradleChange(value: String) {
        val v = value.trim()
        _state.update { it.copy(gradle = v, gradleError = CloudBuildInputValidator.validateGradleVersion(v), preflight = null, rejected = emptyList()) }
        persist()
    }

    private fun persist() {
        val s = _state.value
        val config = s.config ?: return
        if (s.dirError != null || s.gradleError != null) return
        val updated = config.copy(projectDir = s.dir, gradleVersion = s.gradle)
        configs.save(updated)
        _state.update { it.copy(config = updated) }
    }

    /** Read-only check of project, repository, branch and workflow. Uploads nothing. */
    fun check() {
        val s = _state.value
        val project = s.project ?: return
        val config = s.config?.copy(projectDir = s.dir, gradleVersion = s.gradle) ?: return
        _state.update { it.copy(checking = true, preflight = null, rejected = emptyList()) }
        viewModelScope.launch {
            try {
                val result = orchestrator.preflight(project, config)
                _state.update { it.copy(checking = false, preflight = result) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, error = e.toUserMessage()) }
            }
        }
    }

    /** Uploads the project and starts the GitHub Actions build. */
    fun startBuild() {
        val s = _state.value
        val project = s.project ?: return
        val config = s.config?.copy(projectDir = s.dir, gradleVersion = s.gradle) ?: return
        if (!s.canStart) return
        _state.update { it.copy(starting = true, rejected = emptyList(), error = null) }
        viewModelScope.launch {
            try {
                when (val result = tracker.startBuild(project, config)) {
                    is StartResult.Rejected -> _state.update { it.copy(starting = false, rejected = result.reasons) }
                    is StartResult.Started -> {
                        _state.update { it.copy(starting = false) }
                        _startedBuildId.value = result.record.id
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(starting = false, error = e.toUserMessage()) }
            }
        }
    }

    fun consumeStarted() {
        _startedBuildId.value = null
    }

    /** The workflow file the user must add to their repository once. */
    fun workflowText(): String = workflowTemplate.workflowText()
}
