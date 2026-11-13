package com.almus.appcreatorpro.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.workspace.RepoReference
import com.almus.appcreatorpro.workspace.RepoWorkspace
import com.almus.appcreatorpro.workspace.RepoWorkspaceManager
import com.almus.appcreatorpro.workspace.WorkspaceGitHub
import com.almus.appcreatorpro.workspace.WorkspaceHub
import com.almus.appcreatorpro.workspace.WorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProjectsTab(val label: String) {
    REPOSITORIES("Repositories"),
    WORKSPACES("Workspaces"),
}

/** The repository that was tapped: information, branches, newest commit, and whether it is already on this phone. */
data class RepoDetailUi(
    val repo: GitHubRepo,
    val branches: List<GitHubBranch> = emptyList(),
    val selectedBranch: String = repo.defaultBranch,
    val latestCommit: CommitSummary? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Set when this repository and branch already have a local workspace (opening it does not download again). */
    val existing: RepoWorkspace? = null,
)

data class ImportingUi(val title: String, val progress: TransferProgress? = null)

data class MyGitHubProjectsUi(
    val tab: ProjectsTab = ProjectsTab.REPOSITORIES,
    /** True until the first sign-in check finished. */
    val checking: Boolean = true,
    /** The signed-in login. Null = public-only mode (nothing is signed in, or the sign-in could not be verified). */
    val login: String? = null,
    val myRepos: List<GitHubRepo> = emptyList(),
    val myNextPage: Int? = null,
    val loadingMine: Boolean = false,
    val loadingMore: Boolean = false,
    val query: String = "",
    /** Results of a GitHub search; null = no search is showing. */
    val searchResults: List<GitHubRepo>? = null,
    val searchNextPage: Int? = null,
    val searchedText: String = "",
    val searching: Boolean = false,
    val error: String? = null,
    val detail: RepoDetailUi? = null,
    /** Newest opened first: this is the recent repository history. */
    val workspaces: List<RepoWorkspace> = emptyList(),
    /** Workspaces whose local project was deleted. */
    val missingProjects: Set<String> = emptySet(),
    val importing: ImportingUi? = null,
    val message: String? = null,
) {
    /** The signed-in user's repositories narrowed by what was typed (this filter is local; "Search GitHub" asks GitHub). */
    val visibleMine: List<GitHubRepo>
        get() = if (query.isBlank()) myRepos else myRepos.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
}

/**
 * "My GitHub Projects": lists the user's repositories (private ones too when signed in), searches public repositories,
 * shows repository information and branches, and imports a repository into a local workspace.
 */
class MyGitHubProjectsViewModel(
    private val github: WorkspaceGitHub,
    private val manager: RepoWorkspaceManager,
    private val store: WorkspaceStore,
    private val projects: ProjectRepository,
    private val hub: WorkspaceHub,
) : ViewModel() {

    private val _state = MutableStateFlow(MyGitHubProjectsUi(workspaces = store.workspaces.value))
    val state: StateFlow<MyGitHubProjectsUi> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var importJob: Job? = null

    init {
        viewModelScope.launch {
            store.workspaces.collect { list ->
                val missing = list.filter { projects.getProject(it.projectId) == null }.map { it.id }.toSet()
                _state.update { s -> s.copy(workspaces = list, missingProjects = missing) }
            }
        }
        refresh()
    }

    // ------------------------------------------------------------------------------------------ listing

    /** Checks the sign-in and loads the user's repositories. Without a sign-in only public search and links work. */
    fun refresh() {
        loadJob?.cancel()
        _state.update { it.copy(checking = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val login = github.connectedUser()
                _state.update { it.copy(login = login, checking = false) }
                if (login != null) loadMine(1, replace = true)
                else _state.update { it.copy(myRepos = emptyList(), myNextPage = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(checking = false, error = e.toUserMessage()) }
            }
        }
    }

    private suspend fun loadMine(page: Int, replace: Boolean) {
        _state.update { it.copy(loadingMine = replace, loadingMore = !replace) }
        try {
            val result = github.myRepositories(page)
            _state.update {
                it.copy(
                    myRepos = if (replace) result.items else it.myRepos + result.items,
                    myNextPage = result.nextPage,
                    loadingMine = false,
                    loadingMore = false,
                    error = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loadingMine = false, loadingMore = false, error = e.toUserMessage()) }
        }
    }

    fun loadMoreMine() {
        val s = _state.value
        val next = s.myNextPage ?: return
        if (s.loadingMore || s.loadingMine) return
        viewModelScope.launch { loadMine(next, replace = false) }
    }

    fun setTab(tab: ProjectsTab) = _state.update { it.copy(tab = tab) }

    // ------------------------------------------------------------------------------------------ search

    fun onQueryChange(text: String) {
        _state.update { if (text.isBlank()) it.copy(query = text, searchResults = null, searchNextPage = null, searchedText = "") else it.copy(query = text) }
    }

    /**
     * Asks GitHub. A pasted link or `owner/repo` opens that repository directly (works for any public repository, and for
     * private ones the sign-in can read); anything else is a text search over public repositories.
     */
    fun searchGitHub() {
        val text = _state.value.query.trim()
        if (_state.value.searching) return
        if (text.length < 2) {
            _state.update { it.copy(message = "Type at least 2 characters to search GitHub.") }
            return
        }
        val ref = RepoReference.parse(text)
        _state.update { it.copy(searching = true, error = null) }
        viewModelScope.launch {
            try {
                if (ref != null) {
                    val repo = github.lookup(ref.owner, ref.name)
                    _state.update { it.copy(searching = false) }
                    openRepo(repo)
                } else {
                    val page = github.search(text, 1)
                    _state.update { it.copy(searching = false, searchResults = page.items, searchNextPage = page.nextPage, searchedText = text) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.toUserMessage()) }
            }
        }
    }

    fun loadMoreSearch() {
        val s = _state.value
        val next = s.searchNextPage ?: return
        if (s.searching || s.searchedText.isBlank()) return
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            try {
                val page = github.search(s.searchedText, next)
                _state.update { it.copy(searching = false, searchResults = it.searchResults.orEmpty() + page.items, searchNextPage = page.nextPage) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = e.toUserMessage()) }
            }
        }
    }

    fun clearSearch() = _state.update { it.copy(searchResults = null, searchNextPage = null, searchedText = "") }

    // ------------------------------------------------------------------------------------------ repository detail

    fun openRepo(repo: GitHubRepo) {
        detailJob?.cancel()
        _state.update {
            it.copy(detail = RepoDetailUi(repo, existing = store.find(repo.owner, repo.name, repo.defaultBranch)))
        }
        detailJob = viewModelScope.launch {
            try {
                val branches = github.branches(repo.owner, repo.name)
                val chosen = when {
                    branches.any { it.name == repo.defaultBranch } -> repo.defaultBranch
                    else -> branches.firstOrNull()?.name ?: repo.defaultBranch
                }
                val commit = github.latestCommit(repo.owner, repo.name, chosen)
                updateDetail(repo.id) {
                    it.copy(branches = branches, selectedBranch = chosen, latestCommit = commit, loading = false, error = null,
                        existing = store.find(repo.owner, repo.name, chosen))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetail(repo.id) { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun selectBranch(name: String) {
        val detail = _state.value.detail ?: return
        val repo = detail.repo
        detailJob?.cancel()
        updateDetail(repo.id) {
            it.copy(selectedBranch = name, latestCommit = null, loading = true, error = null, existing = store.find(repo.owner, repo.name, name))
        }
        detailJob = viewModelScope.launch {
            try {
                val commit = github.latestCommit(repo.owner, repo.name, name)
                updateDetail(repo.id) { if (it.selectedBranch == name) it.copy(latestCommit = commit, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateDetail(repo.id) { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        _state.update { it.copy(detail = null) }
    }

    private fun updateDetail(repoId: Long, change: (RepoDetailUi) -> RepoDetailUi) {
        _state.update { s ->
            val d = s.detail
            if (d != null && d.repo.id == repoId) s.copy(detail = change(d)) else s
        }
    }

    // ------------------------------------------------------------------------------------------ import and open

    /** Downloads the selected repository and branch into a local workspace (or opens the existing one), then calls [onOpened]. */
    fun importSelected(onOpened: (workspaceId: String) -> Unit) {
        val detail = _state.value.detail ?: return
        if (_state.value.importing != null) return
        val repo = detail.repo
        val branch = detail.selectedBranch
        _state.update { it.copy(importing = ImportingUi("Preparing ${repo.fullName}")) }
        importJob = viewModelScope.launch {
            try {
                val workspace = manager.openOrImport(repo, branch) { progress ->
                    _state.update { s ->
                        val current = s.importing
                        if (current == null) s else s.copy(importing = current.copy(title = "${progress.phase.label} ${repo.fullName}", progress = progress))
                    }
                }
                _state.update { it.copy(importing = null, detail = null) }
                onOpened(workspace.id)
            } catch (e: CancellationException) {
                _state.update { it.copy(importing = null) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(importing = null, message = e.toUserMessage()) }
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
    }

    /** Opens a workspace that is already on the phone (project switching and recent history). */
    fun openWorkspace(workspace: RepoWorkspace, onOpened: (String) -> Unit) {
        viewModelScope.launch {
            try {
                if (manager.projectExists(workspace)) {
                    store.markOpened(workspace.id)
                    onOpened(workspace.id)
                } else {
                    _state.update { it.copy(message = "The local copy of ${workspace.fullName} was deleted. Import it again from the Repositories tab.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    /** Removes the workspace from this list and clears its AI conversation. The local project and its files stay. */
    fun forgetWorkspace(workspace: RepoWorkspace) {
        viewModelScope.launch {
            try {
                hub.discard(workspace.id)
                manager.forget(workspace)
                _state.update { it.copy(message = "Removed ${workspace.fullName} from workspaces. Its project is still in Projects.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.toUserMessage()) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
