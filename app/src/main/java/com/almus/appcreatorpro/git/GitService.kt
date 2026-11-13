package com.almus.appcreatorpro.git

import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.model.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

data class PullOutcome(val fetch: FetchOutcome, val merge: MergeOutcome)

/**
 * Everything the Git screen does. Adds to [GitEngine]: input validation, one mutating operation at a time per project,
 * the clean-tree rules, secret detection, and the confirmations. A risky operation without a matching [GitConfirmation]
 * fails with CONFIRMATION_REQUIRED and carries the [GitRisk] to show; the UI cannot skip this step.
 *
 * Credentials come from [credentials] per network call and are never stored here.
 */
class GitService(
    private val workspaces: GitWorkspaceResolver,
    private val engine: GitEngine,
    private val credentials: GitCredentialSource,
    private val identities: GitIdentityStore,
    private val cloneTarget: GitCloneTarget? = null,
    /** Fallback identity when the user has not set one (for example from the GitHub login). */
    private val defaultIdentity: () -> GitIdentity? = { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    // ------------------------------------------------------------------------------------------- identity

    fun identity(): GitIdentity? = identities.get() ?: defaultIdentity()

    fun setIdentity(name: String, email: String) {
        GitInputValidator.identity(name, email)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        identities.set(GitIdentity(name.trim(), email.trim()))
    }

    // ------------------------------------------------------------------------------------------- repository

    /** Null when the project has no repository yet. */
    suspend fun status(projectId: String): GitStatus? = read(projectId) { ws -> if (engine.isRepository(ws)) engine.status(ws) else null }

    suspend fun init(projectId: String, branch: String = "main", createGitignore: Boolean = true) {
        GitInputValidator.branchName(branch)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        mutate(projectId) { ws, _ -> engine.init(ws, branch, createGitignore) }
    }

    /** Clones into a NEW project (the history comes with it). The token is used only for github.com addresses. */
    suspend fun cloneRepository(url: String, branch: String? = null, onProgress: (GitProgress) -> Unit = {}): Project {
        val clean = url.trim()
        GitInputValidator.remoteUrl(clean)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        branch?.let { b -> GitInputValidator.branchName(b)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) } }
        val target = cloneTarget ?: throw GitException(GitException.Kind.UNKNOWN, "Cloning is not available here.")
        val staging = target.newStagingDir()
        try {
            withContext(ioDispatcher) {
                val job = coroutineContext[Job]
                translate {
                    engine.clone(
                        GitWorkspace(File(staging, "files"), File(staging, ProjectGitWorkspaceResolver.GIT_DIR_NAME)),
                        clean, branch, GitContext(credentials, null, onProgress) { job?.isActive == false },
                    )
                }
            }
            return withContext(ioDispatcher) { translate { target.createProject(staging, GitInputValidator.repoNameOf(clean)) } }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + ioDispatcher) { staging.deleteRecursively() }
        }
    }

    // ------------------------------------------------------------------------------------------- staging and commit

    suspend fun stage(projectId: String, paths: List<String>) {
        paths.forEach { p -> GitInputValidator.repoPath(p)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) } }
        mutate(projectId) { ws, _ -> requireRepo(ws); engine.stage(ws, paths) }
    }

    suspend fun stageAll(projectId: String) = mutate(projectId) { ws, _ -> requireRepo(ws); engine.stageAll(ws) }

    suspend fun unstage(projectId: String, paths: List<String>) {
        paths.forEach { p -> GitInputValidator.repoPath(p)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) } }
        mutate(projectId) { ws, _ -> requireRepo(ws); engine.unstage(ws, paths) }
    }

    suspend fun unstageAll(projectId: String) = mutate(projectId) { ws, _ -> requireRepo(ws); engine.unstageAll(ws) }

    /**
     * Commits what is staged. Staged files that look like secrets (keys, passwords, tokens) need a confirmation.
     */
    suspend fun commit(projectId: String, message: String, confirmation: GitConfirmation? = null): GitCommit {
        GitInputValidator.commitMessage(message)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        val who = identity() ?: throw GitException(GitException.Kind.IDENTITY_MISSING, "Set your name and email first (they are saved in each commit).")
        return mutate(projectId) { ws, _ ->
            requireRepo(ws)
            val st = engine.status(ws)
            if (st.isDetached) throw GitException(GitException.Kind.DETACHED_HEAD, "You are not on a branch. Create or switch to a branch before committing.")
            if (st.conflicted.isNotEmpty()) throw GitException(GitException.Kind.CONFLICTS, "Resolve the ${st.conflicted.size} conflicted file(s) first.")
            if (st.staged.isEmpty() && st.state != GitRepoState.MERGING) throw GitException(GitException.Kind.NOTHING_TO_COMMIT, "Nothing is staged. Stage some files first.")
            val findings = secretFindings(ws, st)
            if (findings.isNotEmpty()) {
                val risk = GitRiskAssessor.commitWithSecrets(findings)
                if (confirmation?.satisfies(risk) != true) throw GitException(GitException.Kind.SECRETS_DETECTED, "Possible secrets are staged.", risk)
            }
            engine.commit(ws, message.trim(), who)
        }
    }

    private fun secretFindings(ws: GitWorkspace, st: GitStatus): List<GitSecretGuard.Finding> {
        val paths = st.staged.filter { it.staged != ChangeKind.DELETED }.map { it.path }
        val byName = paths.mapNotNull { p -> GitSecretGuard.checkPath(p)?.let { GitSecretGuard.Finding(p, it) } }
        val byContent = engine.stagedText(ws, paths).flatMap { (p, text) -> if (text == null) emptyList() else GitSecretGuard.scanText(p, text) }
        return (byName + byContent).distinct()
    }

    // ------------------------------------------------------------------------------------------- history

    suspend fun history(projectId: String, skip: Int = 0, limit: Int = 50): List<GitCommit> =
        read(projectId) { ws -> requireRepo(ws); engine.log(ws, skip.coerceAtLeast(0), limit.coerceIn(1, 200)) }

    suspend fun commitFiles(projectId: String, sha: String): List<GitCommitFile> {
        if (!Regex("^[0-9a-fA-F]{7,40}$").matches(sha)) throw GitException(GitException.Kind.INVALID_INPUT, "That is not a commit id.")
        return read(projectId) { ws -> requireRepo(ws); engine.commitFiles(ws, sha) }
    }

    // ------------------------------------------------------------------------------------------- branches

    suspend fun branches(projectId: String): List<GitBranch> = read(projectId) { ws -> requireRepo(ws); engine.branches(ws) }

    suspend fun createBranch(projectId: String, name: String, checkout: Boolean = true) {
        GitInputValidator.branchName(name)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        mutate(projectId) { ws, _ ->
            requireRepo(ws)
            requireNoMergeInProgress(engine.status(ws))
            if (engine.branches(ws).any { !it.isRemote && it.name == name }) throw GitException(GitException.Kind.ALREADY_EXISTS, "A branch named \"$name\" already exists.")
            engine.createBranch(ws, name, checkout)
        }
    }

    /** [name] is a local branch ("main") or a remote one ("origin/feature", which creates a tracking branch). */
    suspend fun switchBranch(projectId: String, name: String) {
        GitInputValidator.branchName(name)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        mutate(projectId) { ws, _ ->
            requireRepo(ws)
            requireNoMergeInProgress(engine.status(ws))
            engine.checkout(ws, name)
        }
    }

    /** Every deletion needs a confirmation; an unmerged branch needs its name typed. The state is re-checked here, at execution time. */
    suspend fun deleteBranch(projectId: String, name: String, confirmation: GitConfirmation? = null) {
        GitInputValidator.branchName(name)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        mutate(projectId) { ws, _ ->
            requireRepo(ws)
            val preview = engine.deletePreview(ws, name)
            if (preview.isCurrent) throw GitException(GitException.Kind.INVALID_INPUT, "You are on \"$name\". Switch to another branch first.")
            val risk = GitRiskAssessor.deleteBranch(preview)
            if (confirmation?.satisfies(risk) != true) throw GitException(GitException.Kind.CONFIRMATION_REQUIRED, risk.message, risk)
            engine.deleteBranch(ws, name, force = !preview.isMerged)
        }
    }

    // ------------------------------------------------------------------------------------------- merge and conflicts

    suspend fun merge(projectId: String, branch: String): MergeOutcome {
        GitInputValidator.branchName(branch)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        val who = identity() ?: throw GitException(GitException.Kind.IDENTITY_MISSING, "Set your name and email first (a merge commit needs them).")
        return mutate(projectId) { ws, _ ->
            requireRepo(ws)
            val st = engine.status(ws)
            requireReadyToIntegrate(st)
            if (st.branch == branch) throw GitException(GitException.Kind.INVALID_INPUT, "A branch cannot be merged into itself.")
            engine.merge(ws, branch, branch, who)
        }
    }

    suspend fun conflicts(projectId: String): List<ConflictFile> = read(projectId) { ws ->
        requireRepo(ws)
        engine.status(ws).conflicted.map { c ->
            val text = readSmallText(File(ws.workTree, c.path))
            ConflictFile(c.path, text?.let { GitConflicts.countRegions(it) } ?: 0, text?.let { GitConflicts.hasMarkers(it) } ?: false)
        }
    }

    /** After the user edited the file: refuses while conflict markers are still in it. */
    suspend fun markResolved(projectId: String, path: String) {
        GitInputValidator.repoPath(path)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        mutate(projectId) { ws, _ ->
            requireRepo(ws)
            val text = readSmallText(File(ws.workTree, path))
            if (text != null && GitConflicts.hasMarkers(text)) throw GitException(GitException.Kind.CONFLICTS, "\"$path\" still contains conflict markers (<<<<<<<, =======, >>>>>>>). Edit the file first.")
            engine.stage(ws, listOf(path))
        }
    }

    suspend fun resolveWithSide(projectId: String, path: String, side: ConflictSide, confirmation: GitConfirmation? = null) {
        GitInputValidator.repoPath(path)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        mutate(projectId) { ws, _ ->
            requireRepo(ws)
            val risk = GitRiskAssessor.resolveWithSide(path, side)
            if (confirmation?.satisfies(risk) != true) throw GitException(GitException.Kind.CONFIRMATION_REQUIRED, risk.message, risk)
            engine.resolveWithSide(ws, path, side)
        }
    }

    suspend fun abortMerge(projectId: String, confirmation: GitConfirmation? = null) {
        mutate(projectId) { ws, _ ->
            requireRepo(ws)
            val st = engine.status(ws)
            if (st.state != GitRepoState.MERGING) throw GitException(GitException.Kind.INVALID_INPUT, "No merge is in progress.")
            val risk = GitRiskAssessor.abortMerge(st.conflicted.size)
            if (confirmation?.satisfies(risk) != true) throw GitException(GitException.Kind.CONFIRMATION_REQUIRED, risk.message, risk)
            engine.abortMerge(ws)
        }
    }

    // ------------------------------------------------------------------------------------------- remotes

    suspend fun remotes(projectId: String): List<GitRemote> = read(projectId) { ws -> requireRepo(ws); engine.remotes(ws) }

    suspend fun setRemote(projectId: String, name: String, url: String) {
        GitInputValidator.remoteName(name)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        GitInputValidator.remoteUrl(url)?.let { throw GitException(GitException.Kind.INVALID_INPUT, it) }
        mutate(projectId) { ws, _ -> requireRepo(ws); engine.setRemote(ws, name, url.trim()) }
    }

    suspend fun fetch(projectId: String, onProgress: (GitProgress) -> Unit = {}): FetchOutcome =
        mutate(projectId) { ws, cancelled ->
            requireRepo(ws)
            engine.fetch(ws, defaultRemote(ws), GitContext(credentials, null, onProgress, cancelled))
        }

    /** Fetch, then merge the upstream branch. Refuses to start when local changes or a merge are in the way. */
    suspend fun pull(projectId: String, onProgress: (GitProgress) -> Unit = {}): PullOutcome {
        val who = identity() ?: throw GitException(GitException.Kind.IDENTITY_MISSING, "Set your name and email first (a merge commit needs them).")
        return mutate(projectId) { ws, cancelled ->
            requireRepo(ws)
            val before = engine.status(ws)
            requireReadyToIntegrate(before)
            val branch = before.branch!!
            val upstream = before.upstream
            val remote = upstream?.substringBefore('/') ?: defaultRemote(ws)
            val fetched = engine.fetch(ws, remote, GitContext(credentials, who, onProgress, cancelled))
            val target = upstream ?: "$remote/$branch"
            if (engine.branches(ws).none { it.isRemote && it.name == target }) {
                throw GitException(GitException.Kind.NOT_FOUND, "The remote has no branch \"$branch\" yet. Push it first.")
            }
            PullOutcome(fetched, engine.merge(ws, "refs/remotes/$target", target, who))
        }
    }

    suspend fun pushPreview(projectId: String): PushPreview = read(projectId) { ws ->
        requireRepo(ws)
        engine.commitsToPush(ws, defaultRemote(ws))
    }

    /** Never forced. The user must confirm the summary first. */
    suspend fun push(projectId: String, confirmation: GitConfirmation? = null, onProgress: (GitProgress) -> Unit = {}): PushOutcome =
        mutate(projectId) { ws, cancelled ->
            requireRepo(ws)
            val st = engine.status(ws)
            if (st.state == GitRepoState.MERGING) throw GitException(GitException.Kind.MERGE_IN_PROGRESS, "Finish or abort the merge before pushing.")
            val remote = defaultRemote(ws)
            val preview = engine.commitsToPush(ws, remote)
            if (preview.commits == 0 && !preview.createsRemoteBranch) return@mutate PushOutcome(remote, preview.branch, upToDate = true)
            val risk = GitRiskAssessor.push(preview)
            if (confirmation?.satisfies(risk) != true) throw GitException(GitException.Kind.CONFIRMATION_REQUIRED, risk.message, risk)
            engine.push(ws, remote, setUpstream = st.upstream == null, ctx = GitContext(credentials, null, onProgress, cancelled))
        }

    // ------------------------------------------------------------------------------------------- internals

    private fun requireRepo(ws: GitWorkspace) {
        if (!engine.isRepository(ws)) throw GitException(GitException.Kind.NOT_A_REPOSITORY, "This project is not a Git repository yet. Initialize it first.")
    }

    private fun requireNoMergeInProgress(st: GitStatus) {
        if (st.state != GitRepoState.CLEAN_STATE) throw GitException(GitException.Kind.MERGE_IN_PROGRESS, "A merge is in progress. Finish it (commit) or abort it first.")
    }

    /** Merge and pull only start from a clean tracked state: nothing of the user's work can be mixed up or lost. */
    private fun requireReadyToIntegrate(st: GitStatus) {
        requireNoMergeInProgress(st)
        if (st.isDetached) throw GitException(GitException.Kind.DETACHED_HEAD, "You are not on a branch. Switch to a branch first.")
        if (!st.hasCommits) throw GitException(GitException.Kind.NO_COMMITS, "Make a first commit first.")
        if (st.hasTrackedChanges) throw GitException(GitException.Kind.UNCOMMITTED_CHANGES, "You have uncommitted changes. Commit them first, then try again.")
    }

    private fun defaultRemote(ws: GitWorkspace): String {
        val remotes = engine.remotes(ws)
        return remotes.firstOrNull { it.name == "origin" }?.name ?: remotes.firstOrNull()?.name
            ?: throw GitException(GitException.Kind.NO_REMOTE, "No remote repository is set up. Add one in the Remote tab.")
    }

    private fun readSmallText(file: File): String? =
        try { if (file.isFile && file.length() < 512 * 1024) file.readText(Charsets.UTF_8) else null } catch (e: Exception) { null }

    private suspend fun <T> read(projectId: String, block: (GitWorkspace) -> T): T {
        val ws = resolveWorkspace(projectId)
        return withContext(ioDispatcher) { translate { block(ws) } }
    }

    /** One mutating operation per project at a time; a second one fails fast instead of queueing up. */
    private suspend fun <T> mutate(projectId: String, block: (GitWorkspace, () -> Boolean) -> T): T {
        val ws = resolveWorkspace(projectId)
        val mutex = locks.getOrPut(projectId) { Mutex() }
        if (!mutex.tryLock()) throw GitException(GitException.Kind.BUSY, "Another Git operation is still running on this project.")
        try {
            return withContext(ioDispatcher) {
                val job = coroutineContext[Job]
                translate { block(ws) { job?.isActive == false } }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun resolveWorkspace(projectId: String): GitWorkspace = try {
        workspaces.resolve(projectId)
    } catch (e: StorageException) {
        throw GitException(GitException.Kind.NOT_FOUND, "This project no longer exists.")
    }

    private inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: GitException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw GitException(GitException.Kind.UNKNOWN, "Git could not finish: ${e.message ?: e.javaClass.simpleName}")
    }
}
