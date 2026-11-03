package com.almus.appcreatorpro.ui.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.data.transfer.ProjectImportService
import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.transfer.ImportPreview
import com.almus.appcreatorpro.domain.transfer.ImportSourceKind
import com.almus.appcreatorpro.domain.transfer.StagedProject
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import com.almus.appcreatorpro.service.transfer.DocumentAccess
import com.almus.appcreatorpro.service.transfer.GitHubSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The screen is always in exactly one of these steps. */
sealed interface ImportUiState {
    /** Choose where to import from. */
    data object Idle : ImportUiState

    /** Browsing GitHub repositories (details in [ImportViewModel.github]). */
    data object GitHubBrowse : ImportUiState

    /** Downloading / unpacking / saving. [progress] is null while nothing has been reported yet. */
    data class Working(val title: String, val progress: TransferProgress?, val saving: Boolean = false) : ImportUiState

    /** Unpacked and checked; waiting for the user to confirm. Nothing has been saved yet. */
    data class Preview(val preview: ImportPreview, val name: String, val nameError: String?) : ImportUiState

    data class Done(val project: Project, val preview: ImportPreview) : ImportUiState

    data class Failed(val message: String) : ImportUiState
}

data class GitHubImportState(
    /** Null until the connection was checked. */
    val connected: Boolean? = null,
    val login: String? = null,
    val repos: List<GitHubRepo> = emptyList(),
    val nextPage: Int? = null,
    val query: String = "",
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val selected: GitHubRepo? = null,
    val branches: List<GitHubBranch> = emptyList(),
    val branchesLoading: Boolean = false,
    val selectedBranch: String? = null,
) {
    val visibleRepos: List<GitHubRepo>
        get() = if (query.isBlank()) repos else repos.filter { it.fullName.contains(query.trim(), ignoreCase = true) }
}

/**
 * Drives the whole import screen: ZIP file, local folder (both through the Storage Access Framework) and GitHub.
 * Every source ends in the same preview step, and nothing becomes a project until the user confirms it.
 */
class ImportViewModel(
    private val service: ProjectImportService,
    private val documents: DocumentAccess,
    private val projects: ProjectRepository,
    private val github: GitHubSource,
    /** Blocking Android file calls (opening a picked file) run here, never on the main thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    private val _github = MutableStateFlow(GitHubImportState())
    val githubState: StateFlow<GitHubImportState> = _github.asStateFlow()

    private var job: Job? = null
    private var staged: StagedProject? = null
    private var existingNames: List<String> = emptyList()

    // ------------------------------------------------------------------------------------------ ZIP / folder

    /** [uri] comes from the system file picker (ACTION_OPEN_DOCUMENT). */
    fun importZip(uri: String) {
        if (isBusy()) return
        runStaging("Reading ZIP file") {
            val info = withContext(ioDispatcher) { documents.describe(uri) }
            val stream = withContext(ioDispatcher) { documents.openInput(uri) }
            service.stageZip(
                input = stream,
                sizeHint = info.sizeBytes,
                sourceName = info.displayName,
                kind = ImportSourceKind.ZIP_FILE,
                originNote = info.displayName?.let { "Imported from ZIP file: $it" }.orEmpty(),
                onProgress = { onProgress(it) },
            )
        }
    }

    /** [treeUri] comes from the system folder picker (ACTION_OPEN_DOCUMENT_TREE). */
    fun importFolder(treeUri: String) {
        if (isBusy()) return
        runStaging("Reading folder") {
            val tree = withContext(ioDispatcher) { documents.treeSource(treeUri) }
            service.stageTree(tree, originNote = "Imported from a folder on this device", onProgress = { onProgress(it) })
        }
    }

    // ------------------------------------------------------------------------------------------ GitHub

    fun openGitHub() {
        if (isBusy()) return
        _state.value = ImportUiState.GitHubBrowse
        loadRepositories()
    }

    fun loadRepositories() {
        _github.update { GitHubImportState(loading = true, query = it.query) }
        viewModelScope.launch {
            try {
                val login = github.connectedUser()
                if (login == null) {
                    _github.update { it.copy(connected = false, loading = false) }
                    return@launch
                }
                val page = github.listRepositories(1)
                _github.update { it.copy(connected = true, login = login, repos = page.items, nextPage = page.nextPage, loading = false, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _github.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun loadMoreRepositories() {
        val current = _github.value
        val next = current.nextPage ?: return
        if (current.loadingMore) return
        _github.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = github.listRepositories(next)
                _github.update { it.copy(repos = it.repos + page.items, nextPage = page.nextPage, loadingMore = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _github.update { it.copy(loadingMore = false, error = e.toUserMessage()) }
            }
        }
    }

    fun onRepositoryQuery(value: String) = _github.update { it.copy(query = value) }

    fun selectRepository(repo: GitHubRepo) {
        _github.update { it.copy(selected = repo, branches = emptyList(), branchesLoading = true, selectedBranch = repo.defaultBranch, error = null) }
        viewModelScope.launch {
            try {
                val branches = github.listBranches(repo.owner, repo.name)
                _github.update { current ->
                    if (current.selected?.id != repo.id) {
                        current
                    } else {
                        val keep = current.selectedBranch?.takeIf { wanted -> branches.any { it.name == wanted } }
                        current.copy(branches = branches, branchesLoading = false, selectedBranch = keep ?: branches.firstOrNull()?.name ?: repo.defaultBranch)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _github.update { it.copy(branchesLoading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun clearSelectedRepository() = _github.update { it.copy(selected = null, branches = emptyList(), selectedBranch = null) }

    fun selectBranch(name: String) = _github.update { it.copy(selectedBranch = name) }

    /** Downloads the selected repository at the selected branch (exactly the branch's current commit). */
    fun importSelectedRepository() {
        val current = _github.value
        val repo = current.selected ?: return
        val branchName = current.selectedBranch ?: repo.defaultBranch
        val branch = current.branches.firstOrNull { it.name == branchName }
        if (isBusy()) return
        val ref = branch?.headSha?.takeIf { it.isNotBlank() } ?: branchName
        runStaging("Downloading ${repo.fullName}") {
            val tmp = service.newTempFile("github-", ".zip")
            try {
                github.download(repo.owner, repo.name, ref, tmp) { onProgress(it) }
                val note = "Imported from GitHub: ${repo.fullName} (branch $branchName" +
                    (branch?.headSha?.takeIf { it.isNotBlank() }?.let { ", commit ${it.take(7)}" } ?: "") + ")"
                service.stageZipFile(
                    file = tmp,
                    sourceName = repo.name,
                    kind = ImportSourceKind.GITHUB,
                    stripSingleRoot = true,
                    originNote = note,
                    onProgress = { onProgress(it) },
                )
            } finally {
                tmp.delete()
            }
        }
    }

    // ------------------------------------------------------------------------------------------ preview / commit

    fun onNameChange(value: String) {
        val current = _state.value as? ImportUiState.Preview ?: return
        _state.value = current.copy(name = value, nameError = validateName(value))
    }

    fun confirmImport() {
        val current = _state.value as? ImportUiState.Preview ?: return
        val stagedProject = staged ?: return
        val error = validateName(current.name)
        if (error != null) {
            _state.value = current.copy(nameError = error)
            return
        }
        if (!current.preview.canImport) return
        _state.value = ImportUiState.Working("Saving project", TransferProgress(TransferProgress.Phase.SAVING), saving = true)
        job = viewModelScope.launch {
            try {
                val project = service.commit(stagedProject, current.name.trim())
                staged = null
                _state.value = ImportUiState.Done(project, current.preview)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ValidationException) {
                // e.g. the name was taken in the meantime: keep the staged files and let the user fix it
                _state.value = current.copy(nameError = e.toUserMessage())
            } catch (e: Exception) {
                discardStaged()
                _state.value = ImportUiState.Failed(e.toUserMessage())
            }
        }
    }

    // ------------------------------------------------------------------------------------------ navigation

    /** Back / cancel: stops work, throws staged files away and returns one step. Returns true if it handled the event. */
    fun back(): Boolean {
        return when (_state.value) {
            ImportUiState.Idle -> false
            ImportUiState.GitHubBrowse -> {
                if (_github.value.selected != null) clearSelectedRepository() else _state.value = ImportUiState.Idle
                true
            }
            is ImportUiState.Working -> {
                cancel()
                true
            }
            is ImportUiState.Preview, is ImportUiState.Failed, is ImportUiState.Done -> {
                reset()
                true
            }
        }
    }

    /** Stops a running import or download. */
    fun cancel() {
        job?.cancel()
        job = null
        discardStaged()
        _state.value = ImportUiState.Idle
    }

    fun reset() {
        job?.cancel()
        job = null
        discardStaged()
        _state.value = ImportUiState.Idle
    }

    override fun onCleared() {
        job?.cancel()
        val leftover = staged
        staged = null
        // viewModelScope is already cancelled here, so use a short-lived thread for the clean-up.
        if (leftover != null) Thread { leftover.dir.deleteRecursively() }.start()
        super.onCleared()
    }

    // ------------------------------------------------------------------------------------------ internals

    private fun isBusy(): Boolean = _state.value is ImportUiState.Working

    private fun onProgress(progress: TransferProgress) {
        val current = _state.value as? ImportUiState.Working ?: return
        if (current.saving) return
        _state.value = current.copy(progress = progress)
    }

    private fun runStaging(title: String, block: suspend () -> StagedProject) {
        discardStaged()
        _state.value = ImportUiState.Working(title, null)
        job = viewModelScope.launch {
            try {
                existingNames = projects.getProjects().map { it.name }
                val result = block()
                staged = result
                val name = result.preview.suggestedName
                _state.value = ImportUiState.Preview(result.preview, name, validateName(name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = ImportUiState.Failed(e.toUserMessage())
            }
        }
    }

    private fun validateName(value: String): String? =
        if (value.isEmpty()) null else ProjectNameValidator.validate(value, existingNames).errorMessage

    private fun discardStaged() {
        val s = staged ?: return
        staged = null
        viewModelScope.launch { service.discard(s) }
    }
}
