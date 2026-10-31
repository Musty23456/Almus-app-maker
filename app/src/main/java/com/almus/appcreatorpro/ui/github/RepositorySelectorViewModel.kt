package com.almus.appcreatorpro.ui.github

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.github.CloudBuildInputValidator
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.GrantedAccess
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The repository that was tapped, with its branches and newest commit. */
data class RepoDetail(
    val repo: GitHubRepo,
    val branches: List<GitHubBranch> = emptyList(),
    val selectedBranch: String = repo.defaultBranch,
    val latestCommit: CommitSummary? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

data class RepoSelectorState(
    /** False until GitHub has confirmed the sign-in. */
    val connected: Boolean = false,
    val access: GrantedAccess? = null,
    val repos: List<GitHubRepo> = emptyList(),
    val query: String = "",
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val nextPage: Int? = null,
    val detail: RepoDetail? = null,
    val message: String? = null,
    val working: Boolean = false,
) {
    val visibleRepos: List<GitHubRepo>
        get() = if (query.isBlank()) repos else repos.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
}

/**
 * Lists the repositories this sign-in may use, lets the user pick one plus a branch, and saves that choice for the
 * project ([projectId]). With [NO_PROJECT] the choice is only remembered as the default for new projects.
 */
class RepositorySelectorViewModel(
    private val projectId: String,
    private val auth: GitHubAuthManager,
    private val configs: CloudBuildConfigStore,
    private val projects: ProjectRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(RepoSelectorState())
    val state: StateFlow<RepoSelectorState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { RepoSelectorState(loading = true, query = it.query) }
        viewModelScope.launch {
            try {
                auth.ensureVerified()
                val current = auth.state.value
                if (current !is GitHubAuthState.Connected) {
                    _state.update { it.copy(connected = false, loading = false) }
                    return@launch
                }
                _state.update { it.copy(connected = true, access = current.access) }
                val page = auth.api.listRepositories(1, current.access.method == AuthMethod.GITHUB_APP)
                _state.update { it.copy(repos = page.items, nextPage = page.nextPage, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val next = s.nextPage ?: return
        val access = s.access ?: return
        if (s.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = auth.api.listRepositories(next, access.method == AuthMethod.GITHUB_APP)
                _state.update { it.copy(repos = it.repos + page.items, nextPage = page.nextPage, loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, error = e.toUserMessage()) }
            }
        }
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value) }

    fun openRepo(repo: GitHubRepo) {
        if (!repo.canPush) {
            _state.update { it.copy(message = "You only have read access to ${repo.fullName}, so it cannot receive uploads.") }
            return
        }
        val saved = configs.get(projectId)?.takeIf { it.repoOwner == repo.owner && it.repoName == repo.name }
        _state.update { it.copy(detail = RepoDetail(repo, selectedBranch = saved?.branch ?: repo.defaultBranch)) }
        loadBranches()
    }

    fun closeDetail() = _state.update { it.copy(detail = null) }

    private fun loadBranches(selectAfter: String? = null) {
        val detail = _state.value.detail ?: return
        _state.update { it.copy(detail = detail.copy(loading = true, error = null)) }
        viewModelScope.launch {
            try {
                val branches = auth.api.listBranches(detail.repo.owner, detail.repo.name)
                val wanted = selectAfter ?: detail.selectedBranch
                val chosen = if (branches.any { it.name == wanted }) wanted else branches.firstOrNull { it.name == detail.repo.defaultBranch }?.name ?: branches.firstOrNull()?.name ?: wanted
                val commit = auth.api.getLatestCommit(detail.repo.owner, detail.repo.name, chosen)
                updateDetail { it.copy(branches = branches, selectedBranch = chosen, latestCommit = commit, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetail { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun selectBranch(name: String) {
        val detail = _state.value.detail ?: return
        updateDetail { it.copy(selectedBranch = name, loading = true, error = null, latestCommit = null) }
        viewModelScope.launch {
            try {
                val commit = auth.api.getLatestCommit(detail.repo.owner, detail.repo.name, name)
                updateDetail { if (it.selectedBranch == name) it.copy(latestCommit = commit, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetail { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    /** Creates [name] from the repository's default branch (a safe place for Almus uploads). */
    fun createBranch(name: String) {
        val detail = _state.value.detail ?: return
        CloudBuildInputValidator.validateBranchName(name)?.let { err ->
            updateDetail { it.copy(error = err) }
            return
        }
        val base = detail.branches.firstOrNull { it.name == detail.repo.defaultBranch }
        if (base == null || base.headSha.isBlank()) {
            updateDetail { it.copy(error = "Could not find the default branch to copy from.") }
            return
        }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                auth.api.createBranch(detail.repo.owner, detail.repo.name, name, base.headSha)
                _state.update { it.copy(working = false, message = "Created branch \"$name\".") }
                loadBranches(selectAfter = name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(working = false) }
                updateDetail { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    /** Only offered when the granted access allows it (OAuth token with a repo scope). */
    fun createRepository(name: String, description: String, isPrivate: Boolean) {
        val access = _state.value.access
        val allowed = if (isPrivate) access?.canCreatePrivateRepository == true else access?.canCreatePublicRepository == true
        if (!allowed) {
            _state.update { it.copy(message = "This sign-in is not allowed to create ${if (isPrivate) "private " else ""}repositories.") }
            return
        }
        if (!Regex("^[A-Za-z0-9._-]{1,100}$").matches(name)) {
            _state.update { it.copy(message = "Repository names may use letters, digits, '.', '-' and '_'.") }
            return
        }
        _state.update { it.copy(working = true) }
        viewModelScope.launch {
            try {
                val created = auth.api.createRepository(name, description.ifBlank { null }, isPrivate)
                _state.update { it.copy(working = false, repos = listOf(created) + it.repos, message = "Created ${created.fullName}.") }
                openRepo(created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(working = false, message = e.toUserMessage()) }
            }
        }
    }

    /** Saves the repository + branch for the project. [onDone] runs after the choice is stored. */
    fun confirm(onDone: () -> Unit) {
        val detail = _state.value.detail ?: return
        viewModelScope.launch {
            val existing = configs.get(projectId)
            val last = configs.last()
            val project = if (projectId == NO_PROJECT) null else projects.getProject(projectId)
            val config = CloudBuildConfig(
                projectId = if (projectId == NO_PROJECT) "" else projectId,
                repoOwner = detail.repo.owner,
                repoName = detail.repo.name,
                branch = detail.selectedBranch,
                projectDir = existing?.projectDir?.takeIf { it.isNotBlank() }
                    ?: project?.let { CloudBuildConfig.defaultDir(it.name) }
                    ?: last?.projectDir.orEmpty(),
                gradleVersion = existing?.gradleVersion ?: CloudBuildConfig.DEFAULT_GRADLE_VERSION,
            )
            if (projectId == NO_PROJECT) configs.saveLast(config) else configs.save(config)
            onDone()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun updateDetail(transform: (RepoDetail) -> RepoDetail) {
        _state.update { s -> s.detail?.let { s.copy(detail = transform(it)) } ?: s }
    }

    companion object {
        /** Route placeholder for "browse repositories without choosing them for a project". */
        const val NO_PROJECT = "_"
    }
}
