package com.almus.appcreatorpro.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.almus.appcreatorpro.git.ConflictFile
import com.almus.appcreatorpro.git.ConflictSide
import com.almus.appcreatorpro.git.GitBranch
import com.almus.appcreatorpro.git.GitCommit
import com.almus.appcreatorpro.git.GitCommitFile
import com.almus.appcreatorpro.git.GitConfirmation
import com.almus.appcreatorpro.git.GitException
import com.almus.appcreatorpro.git.GitIdentity
import com.almus.appcreatorpro.git.GitProgress
import com.almus.appcreatorpro.git.GitRemote
import com.almus.appcreatorpro.git.GitRisk
import com.almus.appcreatorpro.git.GitService
import com.almus.appcreatorpro.git.GitStatus
import com.almus.appcreatorpro.git.MergeStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A risky action waiting for the user's answer. */
class PendingRisk(val risk: GitRisk, internal val retry: suspend (GitConfirmation) -> String?, internal val label: String)

data class GitUiState(
    val loading: Boolean = true,
    val busy: String? = null,
    val progress: GitProgress? = null,
    val hasRepo: Boolean = false,
    val status: GitStatus? = null,
    val history: List<GitCommit> = emptyList(),
    val historyHasMore: Boolean = false,
    val branches: List<GitBranch> = emptyList(),
    val remotes: List<GitRemote> = emptyList(),
    val conflicts: List<ConflictFile> = emptyList(),
    val identity: GitIdentity? = null,
    val githubConnected: Boolean = false,
    val openCommit: Pair<GitCommit, List<GitCommitFile>>? = null,
    val pendingRisk: PendingRisk? = null,
    val error: String? = null,
    val message: String? = null,
)

class GitViewModel(
    private val projectId: String,
    private val service: GitService,
    private val isGitHubConnected: () -> Boolean = { false },
) : ViewModel() {
    private val _state = MutableStateFlow(GitUiState())
    val state: StateFlow<GitUiState> = _state.asStateFlow()
    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            val st = service.status(projectId)
            if (st == null) {
                _state.update { it.copy(loading = false, hasRepo = false, status = null, identity = service.identity(), githubConnected = isGitHubConnected()) }
                return
            }
            val history = if (st.hasCommits) service.history(projectId, 0, PAGE) else emptyList()
            _state.update {
                it.copy(
                    loading = false, hasRepo = true, status = st, history = history, historyHasMore = history.size == PAGE,
                    branches = service.branches(projectId), remotes = service.remotes(projectId),
                    conflicts = if (st.conflicted.isNotEmpty()) service.conflicts(projectId) else emptyList(),
                    identity = service.identity(), githubConnected = isGitHubConnected(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitException) {
            _state.update { it.copy(loading = false, error = e.message) }
        }
    }

    /**
     * Runs one operation. If the service answers with a risk to confirm, the dialog is shown and the SAME operation is
     * repeated with the user's confirmation. Only one operation runs at a time.
     */
    private fun run(label: String, block: suspend (GitConfirmation?) -> String?) {
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = label, error = null) }
        job = viewModelScope.launch {
            try {
                val done = block(null)
                _state.update { it.copy(message = done) }
            } catch (e: CancellationException) {
                _state.update { it.copy(message = "Cancelled.") }
            } catch (e: GitException) {
                val risk = e.risk
                if (risk != null) _state.update { it.copy(pendingRisk = PendingRisk(risk, { c -> block(c) }, label)) }
                else _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = null, progress = null) }
            }
            load()
        }
    }

    private fun progress(p: GitProgress) = _state.update { it.copy(progress = p) }

    fun cancel() { job?.cancel() }

    fun confirmRisk(typedText: String?) {
        val pending = _state.value.pendingRisk ?: return
        val answer = GitConfirmation(true, typedText)
        if (!answer.satisfies(pending.risk)) return
        _state.update { it.copy(pendingRisk = null) }
        run(pending.label) { pending.retry(answer) }
    }

    fun dismissRisk() = _state.update { it.copy(pendingRisk = null) }
    fun messageShown() = _state.update { it.copy(message = null) }
    fun errorShown() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------------------------------------- actions

    fun initRepository() = run("Creating repository") { service.init(projectId); "Repository created." }
    fun stage(path: String) = run("Staging") { service.stage(projectId, listOf(path)); null }
    fun unstage(path: String) = run("Unstaging") { service.unstage(projectId, listOf(path)); null }
    fun stageAll() = run("Staging") { service.stageAll(projectId); null }
    fun unstageAll() = run("Unstaging") { service.unstageAll(projectId); null }

    fun commit(message: String) = run("Committing") { c ->
        val made = service.commit(projectId, message, c)
        "Committed ${made.shortSha}."
    }

    fun fetch() = run("Fetching") { service.fetch(projectId, ::progress).let { "Fetched (${it.updatedRefs} updated)." } }

    fun pull() = run("Pulling") {
        val out = service.pull(projectId, ::progress)
        when (out.merge.status) {
            MergeStatus.UP_TO_DATE -> "Already up to date."
            MergeStatus.FAST_FORWARD -> "Pulled new commits."
            MergeStatus.MERGED -> "Pulled and merged."
            MergeStatus.CONFLICTS -> "Pull stopped: ${out.merge.conflicts.size} file(s) have conflicts."
        }
    }

    fun push() = run("Pushing") { c ->
        val out = service.push(projectId, c, ::progress)
        if (out.upToDate) "Nothing new to push." else "Pushed ${out.branch} to ${out.remote}."
    }

    fun createBranch(name: String) = run("Creating branch") { service.createBranch(projectId, name.trim(), checkout = true); "Switched to new branch ${name.trim()}." }
    fun switchBranch(name: String) = run("Switching branch") { service.switchBranch(projectId, name); "Switched to $name." }
    fun deleteBranch(name: String) = run("Deleting branch") { c -> service.deleteBranch(projectId, name, c); "Deleted branch $name." }

    fun merge(branch: String) = run("Merging") {
        val out = service.merge(projectId, branch)
        when (out.status) {
            MergeStatus.UP_TO_DATE -> "Already up to date."
            MergeStatus.FAST_FORWARD -> "Fast-forwarded to $branch."
            MergeStatus.MERGED -> "Merged $branch."
            MergeStatus.CONFLICTS -> "Merge stopped: ${out.conflicts.size} file(s) have conflicts. Resolve them, then commit."
        }
    }

    fun abortMerge() = run("Aborting merge") { c -> service.abortMerge(projectId, c); "Merge aborted." }
    fun markResolved(path: String) = run("Marking resolved") { service.markResolved(projectId, path); null }
    fun resolveWithSide(path: String, side: ConflictSide) = run("Resolving") { c -> service.resolveWithSide(projectId, path, side, c); null }
    fun setRemote(name: String, url: String) = run("Saving remote") { service.setRemote(projectId, name.trim(), url.trim()); "Remote saved." }

    fun saveIdentity(name: String, email: String) {
        try {
            service.setIdentity(name, email)
            _state.update { it.copy(identity = service.identity(), message = "Saved.") }
        } catch (e: GitException) {
            _state.update { it.copy(error = e.message) }
        }
    }

    fun openCommit(commit: GitCommit) {
        viewModelScope.launch {
            try {
                _state.update { it.copy(openCommit = commit to service.commitFiles(projectId, commit.sha)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun closeCommit() = _state.update { it.copy(openCommit = null) }

    fun loadMoreHistory() {
        val have = _state.value.history.size
        viewModelScope.launch {
            try {
                val more = service.history(projectId, have, PAGE)
                _state.update { it.copy(history = it.history + more, historyHasMore = more.size == PAGE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    private companion object {
        const val PAGE = 50
    }
}
