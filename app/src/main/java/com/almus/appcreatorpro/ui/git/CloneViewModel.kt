package com.almus.appcreatorpro.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.git.GitException
import com.almus.appcreatorpro.git.GitProgress
import com.almus.appcreatorpro.git.GitService
import com.almus.appcreatorpro.service.transfer.GitHubSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CloneUiState(
    val url: String = "",
    val branch: String = "",
    val running: Boolean = false,
    val progress: GitProgress? = null,
    val error: String? = null,
    val githubUser: String? = null,
    val repos: List<GitHubRepo> = emptyList(),
    val reposError: String? = null,
    /** Set when the clone finished: open this project. */
    val createdProjectId: String? = null,
)

class CloneViewModel(private val service: GitService, private val github: GitHubSource) : ViewModel() {
    private val _state = MutableStateFlow(CloneUiState())
    val state: StateFlow<CloneUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            try {
                val user = github.connectedUser()
                _state.update { it.copy(githubUser = user) }
                if (user != null) _state.update { it.copy(repos = github.listRepositories(1).items) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException) {
                _state.update { it.copy(reposError = e.message) }
            }
        }
    }

    fun setUrl(v: String) = _state.update { it.copy(url = v, error = null) }
    fun setBranch(v: String) = _state.update { it.copy(branch = v, error = null) }
    fun pick(repo: GitHubRepo) = _state.update { it.copy(url = repo.htmlUrl.trimEnd('/') + ".git", branch = "", error = null) }

    fun clone() {
        if (_state.value.running) return
        val s = _state.value
        _state.update { it.copy(running = true, error = null, progress = null) }
        job = viewModelScope.launch {
            try {
                val project = service.cloneRepository(s.url, s.branch.trim().ifEmpty { null }) { p -> _state.update { it.copy(progress = p) } }
                _state.update { it.copy(createdProjectId = project.id) }
            } catch (e: CancellationException) {
                _state.update { it.copy(error = "Cloning was cancelled. Nothing was added.") }
            } catch (e: GitException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun navigated() = _state.update { it.copy(createdProjectId = null) }
}
