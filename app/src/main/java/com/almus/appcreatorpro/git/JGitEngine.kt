package com.almus.appcreatorpro.git

import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.CannotDeleteCurrentBranchException
import org.eclipse.jgit.api.errors.CheckoutConflictException
import org.eclipse.jgit.api.errors.InvalidRefNameException
import org.eclipse.jgit.api.errors.NotMergedException
import org.eclipse.jgit.api.errors.RefAlreadyExistsException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.BranchConfig
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.merge.MergeStrategy
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteConfig
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.util.io.NullOutputStream
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.eclipse.jgit.api.errors.TransportException as ApiTransportException
import org.eclipse.jgit.errors.TransportException as CoreTransportException

/**
 * [GitEngine] on top of JGit. The work tree is the project's `files/` folder and the repository data is kept
 * in a separate `git/` folder, so Git never adds anything to the project's own files.
 *
 * Credentials are never stored: they are created per network call from [GitContext.credentials], are pinned to the
 * remote's host ([HostPinnedCredentialsProvider]) and are not written to the repository configuration.
 */
class JGitEngine : GitEngine {

    // ------------------------------------------------------------------------------------------- repository

    override fun isRepository(ws: GitWorkspace): Boolean = File(ws.gitDir, "HEAD").isFile && File(ws.gitDir, "objects").isDirectory

    override fun init(ws: GitWorkspace, defaultBranch: String, createGitignore: Boolean): Unit = guard {
        if (isRepository(ws)) fail(GitException.Kind.ALREADY_A_REPOSITORY, "This project already has a Git repository.")
        ws.workTree.mkdirs()
        Git.init().setDirectory(ws.workTree).setGitDir(ws.gitDir).setInitialBranch(defaultBranch).call().close()
        afterCreate(ws)
        if (createGitignore) {
            val f = File(ws.workTree, ".gitignore")
            if (!f.exists()) f.writeText(DEFAULT_GITIGNORE)
        }
    }

    override fun clone(ws: GitWorkspace, url: String, branch: String?, ctx: GitContext): Unit = guard {
        ws.workTree.mkdirs()
        val cmd = Git.cloneRepository()
            .setURI(url)
            .setDirectory(ws.workTree)
            .setGitDir(ws.gitDir)
            .setCloneAllBranches(true)
            .setTimeout(TIMEOUT_SECONDS)
            .setProgressMonitor(monitor(ctx))
        if (branch != null) cmd.setBranch("refs/heads/$branch")
        provider(url, ctx)?.let { cmd.setCredentialsProvider(it) }
        cmd.call().close()
        afterCreate(ws)
    }

    /** JGit writes a `.git` pointer file into the work tree when the git dir is elsewhere; remove it, it is not needed. */
    private fun afterCreate(ws: GitWorkspace) {
        val pointer = File(ws.workTree, ".git")
        if (pointer.isFile) pointer.delete()
        ensureExclude(ws)
        open(ws) { repo, _ ->
            repo.config.setBoolean("core", null, "filemode", false) // phone storage does not keep the executable bit
            repo.config.save()
        }
    }

    /** Temporary files of the editor's atomic saves must never be staged. */
    private fun ensureExclude(ws: GitWorkspace) {
        val file = File(File(ws.gitDir, "info").also { it.mkdirs() }, "exclude")
        val line = "*" + GitSecretGuard.INTERNAL_SUFFIX
        val current = if (file.isFile) file.readText() else ""
        if (current.lines().none { it.trim() == line }) file.appendText((if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + line + "\n")
    }

    // ------------------------------------------------------------------------------------------- status

    override fun status(ws: GitWorkspace): GitStatus = guard {
        open(ws) { repo, git ->
            val st = git.status().call()
            val staged = HashMap<String, ChangeKind>()
            val unstaged = HashMap<String, ChangeKind>()
            st.added.forEach { staged[it] = ChangeKind.ADDED }
            st.changed.forEach { staged[it] = ChangeKind.MODIFIED }
            st.removed.forEach { staged[it] = ChangeKind.DELETED }
            st.modified.forEach { unstaged[it] = ChangeKind.MODIFIED }
            st.missing.forEach { unstaged[it] = ChangeKind.DELETED }
            st.untracked.forEach { unstaged[it] = ChangeKind.UNTRACKED }
            val conflicting = st.conflicting
            val paths = (staged.keys + unstaged.keys + conflicting).filterNot { GitSecretGuard.isInternalPath(it) }.toSortedSet()
            val changes = paths.map { p ->
                if (p in conflicting) GitFileChange(p, null, null, conflicted = true) else GitFileChange(p, staged[p], unstaged[p])
            }
            val full = repo.fullBranch
            val branch = if (full != null && full.startsWith(Constants.R_HEADS)) Repository.shortenRefName(full) else null
            val head = repo.resolve(Constants.HEAD)?.name
            var upstream: String? = null
            var ahead = 0
            var behind = 0
            if (branch != null) {
                val tracking = org.eclipse.jgit.lib.BranchTrackingStatus.of(repo, branch)
                if (tracking != null) {
                    upstream = Repository.shortenRefName(tracking.remoteTrackingBranch)
                    ahead = tracking.aheadCount
                    behind = tracking.behindCount
                } else {
                    upstream = BranchConfig(repo.config, branch).trackingBranch?.let { Repository.shortenRefName(it) }
                }
            }
            val state = when (repo.repositoryState) {
                RepositoryState.SAFE -> GitRepoState.CLEAN_STATE
                RepositoryState.MERGING, RepositoryState.MERGING_RESOLVED -> GitRepoState.MERGING
                else -> GitRepoState.OTHER
            }
            GitStatus(branch, head, upstream, ahead, behind, changes, state)
        }
    }

    override fun stage(ws: GitWorkspace, paths: List<String>): Unit = guard {
        open(ws) { _, git ->
            val add = git.add()
            val rm = git.rm().setCached(true)
            var adds = 0
            var rms = 0
            for (p in paths.filterNot { GitSecretGuard.isInternalPath(it) }) {
                if (File(ws.workTree, p).exists()) { add.addFilepattern(p); adds++ } else { rm.addFilepattern(p); rms++ }
            }
            if (adds > 0) add.call()
            if (rms > 0) rm.call()
        }
    }

    override fun stageAll(ws: GitWorkspace): Unit = guard {
        ensureExclude(ws)
        open(ws) { _, git ->
            git.add().addFilepattern(".").call()
            git.add().setUpdate(true).addFilepattern(".").call() // also records deleted files
        }
    }

    override fun unstage(ws: GitWorkspace, paths: List<String>): Unit = guard {
        open(ws) { repo, git ->
            if (paths.isEmpty()) return@open
            if (repo.resolve(Constants.HEAD) != null) {
                val reset = git.reset().setRef(Constants.HEAD)
                paths.forEach { reset.addPath(it) }
                reset.call()
            } else {
                val rm = git.rm().setCached(true)
                paths.forEach { rm.addFilepattern(it) }
                rm.call()
            }
        }
    }

    override fun unstageAll(ws: GitWorkspace): Unit = guard {
        open(ws) { repo, git ->
            if (repo.resolve(Constants.HEAD) != null) {
                git.reset().setRef(Constants.HEAD).setMode(ResetCommand.ResetType.MIXED).call()
            } else {
                val dc = repo.lockDirCache()
                try { dc.clear(); dc.write(); dc.commit() } finally { dc.unlock() }
            }
        }
    }

    override fun stagedText(ws: GitWorkspace, paths: List<String>): Map<String, String?> = guard {
        open(ws) { repo, _ ->
            val dc = repo.readDirCache()
            paths.associateWith { p ->
                val entry = dc.getEntry(p) ?: return@associateWith null
                val loader = repo.open(entry.objectId)
                if (loader.size > MAX_SCAN_BYTES) return@associateWith null
                val bytes = loader.bytes
                if (bytes.any { it == 0.toByte() }) null else String(bytes, Charsets.UTF_8)
            }
        }
    }

    override fun commit(ws: GitWorkspace, message: String, identity: GitIdentity): GitCommit = guard {
        open(ws) { repo, git ->
            applyIdentity(repo, identity)
            val who = PersonIdent(identity.name, identity.email)
            toCommit(git.commit().setMessage(message).setAuthor(who).setCommitter(who).call())
        }
    }

    // ------------------------------------------------------------------------------------------- history

    override fun log(ws: GitWorkspace, skip: Int, limit: Int): List<GitCommit> = guard {
        open(ws) { repo, git ->
            val head = repo.resolve(Constants.HEAD) ?: return@open emptyList<GitCommit>()
            git.log().add(head).setSkip(skip).setMaxCount(limit).call().map { toCommit(it) }
        }
    }

    override fun commitFiles(ws: GitWorkspace, sha: String): List<GitCommitFile> = guard {
        open(ws) { repo, _ ->
            val id = repo.resolve(sha) ?: fail(GitException.Kind.NOT_FOUND, "That commit was not found.")
            RevWalk(repo).use { walk ->
                val commit = walk.parseCommit(id)
                val parentTree = if (commit.parentCount > 0) walk.parseCommit(commit.getParent(0).id).tree else null
                DiffFormatter(NullOutputStream.INSTANCE).use { df ->
                    df.setRepository(repo)
                    df.isDetectRenames = true
                    df.scan(parentTree, commit.tree).take(MAX_COMMIT_FILES).map { e ->
                        val kind = when (e.changeType) {
                            DiffEntry.ChangeType.ADD, DiffEntry.ChangeType.COPY -> ChangeKind.ADDED
                            DiffEntry.ChangeType.DELETE -> ChangeKind.DELETED
                            DiffEntry.ChangeType.RENAME -> ChangeKind.RENAMED
                            else -> ChangeKind.MODIFIED
                        }
                        GitCommitFile(if (e.changeType == DiffEntry.ChangeType.DELETE) e.oldPath else e.newPath, kind)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------- branches

    override fun branches(ws: GitWorkspace): List<GitBranch> = guard {
        open(ws) { repo, git ->
            val current = repo.fullBranch
            val local = git.branchList().call().map { ref ->
                val short = Repository.shortenRefName(ref.name)
                GitBranch(
                    name = short,
                    isCurrent = ref.name == current,
                    isRemote = false,
                    headSha = ref.objectId?.name,
                    upstream = BranchConfig(repo.config, short).trackingBranch?.let { Repository.shortenRefName(it) },
                )
            }
            val remote = git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
                .filterNot { it.name.endsWith("/HEAD") }
                .map { GitBranch(Repository.shortenRefName(it.name), isCurrent = false, isRemote = true, headSha = it.objectId?.name, upstream = null) }
            local.sortedBy { it.name.lowercase() } + remote.sortedBy { it.name.lowercase() }
        }
    }

    override fun createBranch(ws: GitWorkspace, name: String, checkout: Boolean): Unit = guard {
        open(ws) { repo, git ->
            if (repo.resolve(Constants.HEAD) == null) fail(GitException.Kind.NO_COMMITS, "Make a first commit before creating branches.")
            git.branchCreate().setName(name).call()
            if (checkout) git.checkout().setName(name).call()
        }
    }

    override fun checkout(ws: GitWorkspace, name: String): Unit = guard {
        open(ws) { repo, git ->
            if (repo.exactRef(Constants.R_HEADS + name) != null) {
                git.checkout().setName(name).call()
            } else if (repo.exactRef(Constants.R_REMOTES + name) != null) {
                // "origin/feature" -> a local "feature" that tracks it
                git.checkout().setCreateBranch(true).setName(name.substringAfter('/'))
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint(Constants.R_REMOTES + name).call()
            } else {
                fail(GitException.Kind.NOT_FOUND, "Branch \"$name\" was not found.")
            }
        }
    }

    override fun deletePreview(ws: GitWorkspace, name: String): BranchDeletePreview = guard {
        open(ws) { repo, git ->
            val ref = repo.exactRef(Constants.R_HEADS + name) ?: fail(GitException.Kind.NOT_FOUND, "Branch \"$name\" was not found.")
            val head = repo.resolve(Constants.HEAD)
            val isCurrent = repo.fullBranch == ref.name
            RevWalk(repo).use { walk ->
                val tip = walk.parseCommit(ref.objectId)
                val merged = head != null && walk.isMergedInto(tip, walk.parseCommit(head))
                var unmerged = 0
                if (!merged) {
                    val log = git.log().add(ref.objectId)
                    if (head != null) log.not(head)
                    unmerged = log.call().take(MAX_COUNT).count()
                }
                var onRemote = false
                for (remote in repo.refDatabase.getRefsByPrefix(Constants.R_REMOTES)) {
                    val id = remote.objectId ?: continue
                    if (walk.isMergedInto(walk.parseCommit(tip.id), walk.parseCommit(id))) { onRemote = true; break }
                }
                BranchDeletePreview(name, isCurrent, merged, unmerged, onRemote)
            }
        }
    }

    override fun deleteBranch(ws: GitWorkspace, name: String, force: Boolean): Unit = guard {
        open(ws) { _, git -> git.branchDelete().setBranchNames(name).setForce(force).call(); Unit }
    }

    // ------------------------------------------------------------------------------------------- merge

    override fun merge(ws: GitWorkspace, ref: String, label: String, identity: GitIdentity): MergeOutcome = guard {
        open(ws) { repo, git ->
            val target = repo.resolve(ref) ?: fail(GitException.Kind.NOT_FOUND, "\"$label\" was not found.")
            if (repo.resolve(Constants.HEAD) == null) fail(GitException.Kind.NO_COMMITS, "Make a first commit before merging.")
            applyIdentity(repo, identity)
            val into = Repository.shortenRefName(repo.fullBranch)
            val result = git.merge().include(label, target)
                .setCommit(true)
                .setFastForward(MergeCommand.FastForwardMode.FF)
                .setStrategy(MergeStrategy.RECURSIVE)
                .setMessage("Merge $label into $into")
                .call()
            val head = repo.resolve(Constants.HEAD)?.name
            when (result.mergeStatus) {
                MergeResult.MergeStatus.ALREADY_UP_TO_DATE -> MergeOutcome(MergeStatus.UP_TO_DATE, newHead = head)
                MergeResult.MergeStatus.FAST_FORWARD -> MergeOutcome(MergeStatus.FAST_FORWARD, newHead = head)
                MergeResult.MergeStatus.MERGED -> MergeOutcome(MergeStatus.MERGED, newHead = head)
                MergeResult.MergeStatus.CONFLICTING ->
                    MergeOutcome(MergeStatus.CONFLICTS, conflicts = (result.conflicts?.keys ?: emptySet()).sorted(), newHead = head)
                MergeResult.MergeStatus.CHECKOUT_CONFLICT, MergeResult.MergeStatus.FAILED -> {
                    val paths = result.failingPaths?.keys.orEmpty().sorted().take(5)
                    fail(GitException.Kind.UNCOMMITTED_CHANGES, "The merge would overwrite local changes" +
                        (if (paths.isNotEmpty()) " in: ${paths.joinToString(", ")}" else "") + ". Commit them first. Nothing was changed.")
                }
                else -> fail(GitException.Kind.UNKNOWN, "The merge could not be completed (${result.mergeStatus}). Nothing was changed.")
            }
        }
    }

    override fun abortMerge(ws: GitWorkspace): Unit = guard {
        open(ws) { repo, git ->
            if (repo.repositoryState == RepositoryState.SAFE) return@open
            git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
            repo.writeMergeCommitMsg(null)
            repo.writeMergeHeads(null)
        }
    }

    override fun resolveWithSide(ws: GitWorkspace, path: String, side: ConflictSide): Unit = guard {
        open(ws) { _, git ->
            try {
                git.checkout().addPath(path)
                    .setStage(if (side == ConflictSide.OURS) CheckoutCommand.Stage.OURS else CheckoutCommand.Stage.THEIRS)
                    .call()
            } catch (e: Exception) {
                fail(GitException.Kind.INVALID_INPUT, "That side has no version of \"$path\" (it was deleted there). Resolve this file by hand.")
            }
            git.add().addFilepattern(path).call()
            Unit
        }
    }

    // ------------------------------------------------------------------------------------------- remotes

    override fun remotes(ws: GitWorkspace): List<GitRemote> = guard {
        open(ws) { repo, _ ->
            RemoteConfig.getAllRemoteConfigs(repo.config).map { rc ->
                GitRemote(rc.name, GitRedactor.stripUserInfo(rc.getURIs().firstOrNull()?.toString().orEmpty()))
            }
        }
    }

    override fun setRemote(ws: GitWorkspace, name: String, url: String): Unit = guard {
        open(ws) { repo, _ ->
            repo.config.setString("remote", name, "url", url)
            repo.config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/$name/*")
            repo.config.save()
        }
    }

    override fun fetch(ws: GitWorkspace, remote: String, ctx: GitContext): FetchOutcome = guard {
        open(ws) { repo, git ->
            val url = repo.config.getString("remote", remote, "url") ?: fail(GitException.Kind.NO_REMOTE, "No remote named \"$remote\" is set up.")
            val cmd = git.fetch().setRemote(remote).setRemoveDeletedRefs(true).setTimeout(TIMEOUT_SECONDS).setProgressMonitor(monitor(ctx))
            provider(url, ctx)?.let { cmd.setCredentialsProvider(it) }
            val result = cmd.call()
            FetchOutcome(result.trackingRefUpdates.count { it.result != RefUpdate.Result.NO_CHANGE }, remote)
        }
    }

    override fun commitsToPush(ws: GitWorkspace, remote: String): PushPreview = guard {
        open(ws) { repo, git ->
            val full = repo.fullBranch
            if (full == null || !full.startsWith(Constants.R_HEADS)) fail(GitException.Kind.DETACHED_HEAD, "You are not on a branch.")
            val branch = Repository.shortenRefName(full)
            val head = repo.resolve(Constants.HEAD) ?: fail(GitException.Kind.NO_COMMITS, "There is nothing to push yet. Make a commit first.")
            val remoteRef = repo.exactRef("${Constants.R_REMOTES}$remote/$branch")
            val log = git.log().add(head)
            if (remoteRef?.objectId != null) log.not(remoteRef.objectId)
            PushPreview(remote, branch, log.call().take(MAX_COUNT).count(), createsRemoteBranch = remoteRef == null)
        }
    }

    override fun push(ws: GitWorkspace, remote: String, setUpstream: Boolean, ctx: GitContext): PushOutcome = guard {
        open(ws) { repo, git ->
            val full = repo.fullBranch
            if (full == null || !full.startsWith(Constants.R_HEADS)) fail(GitException.Kind.DETACHED_HEAD, "You are not on a branch.")
            val branch = Repository.shortenRefName(full)
            val url = repo.config.getString("remote", remote, "url") ?: fail(GitException.Kind.NO_REMOTE, "No remote named \"$remote\" is set up.")
            // No "+" in the refspec and no setForce: a push that is not a fast-forward is refused, never forced.
            val cmd = git.push().setRemote(remote).setRefSpecs(RefSpec("${Constants.R_HEADS}$branch:${Constants.R_HEADS}$branch"))
                .setTimeout(TIMEOUT_SECONDS).setProgressMonitor(monitor(ctx))
            provider(url, ctx)?.let { cmd.setCredentialsProvider(it) }
            var upToDate = true
            for (result in cmd.call()) {
                for (u in result.remoteUpdates) {
                    when (u.status) {
                        RemoteRefUpdate.Status.OK -> upToDate = false
                        RemoteRefUpdate.Status.UP_TO_DATE -> Unit
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED ->
                            fail(GitException.Kind.REJECTED, "The remote has commits you do not have. Pull first, then push again.")
                        RemoteRefUpdate.Status.REJECTED_OTHER_REASON ->
                            fail(GitException.Kind.REJECTED, "The remote refused the push" + (u.message?.let { ": $it" } ?: " (for example a protected branch)."))
                        else -> fail(GitException.Kind.REJECTED, "The push was not accepted (${u.status}).")
                    }
                }
            }
            if (setUpstream) {
                repo.config.setString("branch", branch, "remote", remote)
                repo.config.setString("branch", branch, "merge", "${Constants.R_HEADS}$branch")
                repo.config.save()
            }
            PushOutcome(remote, branch, upToDate)
        }
    }

    // ------------------------------------------------------------------------------------------- helpers

    private inline fun <T> open(ws: GitWorkspace, block: (Repository, Git) -> T): T {
        val repo = FileRepositoryBuilder().setGitDir(ws.gitDir).setWorkTree(ws.workTree).setMustExist(true).build()
        try {
            return block(repo, Git(repo))
        } finally {
            repo.close()
        }
    }

    private fun applyIdentity(repo: Repository, identity: GitIdentity) {
        repo.config.setString("user", null, "name", identity.name)
        repo.config.setString("user", null, "email", identity.email)
        repo.config.save()
    }

    private fun provider(url: String, ctx: GitContext): CredentialsProvider? {
        val credentials = ctx.credentials?.credentialsFor(url) ?: return null
        val host = try { URIish(url).host } catch (e: Exception) { null } ?: return null
        return HostPinnedCredentialsProvider(host, credentials)
    }

    private fun monitor(ctx: GitContext): ProgressMonitor = object : NullProgressMonitor() {
        private var task = ""
        private var total = 0
        private var done = 0
        override fun beginTask(title: String?, totalWork: Int) {
            task = title.orEmpty(); total = totalWork; done = 0
            ctx.onProgress(GitProgress(task, 0, total))
        }
        override fun update(completed: Int) {
            done += completed
            ctx.onProgress(GitProgress(task, done, total))
        }
        override fun isCancelled(): Boolean = ctx.isCancelled()
    }

    private fun toCommit(c: RevCommit): GitCommit {
        val who = c.authorIdent ?: c.committerIdent
        return GitCommit(
            sha = c.name,
            summary = c.shortMessage.orEmpty(),
            message = c.fullMessage.orEmpty().trim(),
            authorName = who?.name.orEmpty(),
            authorEmail = who?.emailAddress.orEmpty(),
            timeMillis = who?.getWhen()?.time ?: (c.commitTime * 1000L),
            parents = c.parents.map { it.name },
        )
    }

    private fun fail(kind: GitException.Kind, message: String): Nothing = throw GitException(kind, message)

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: GitException) {
        throw e
    } catch (e: Exception) {
        throw map(e)
    }

    /** Turns JGit/IO failures into a [GitException] with a plain message. The original exception is dropped on purpose. */
    internal fun map(e: Exception): GitException {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
        val text = chain.joinToString(" | ") { it.message.orEmpty() }.lowercase()
        fun has(vararg parts: String) = parts.any { text.contains(it) }
        return when {
            chain.any { it is RepositoryNotFoundException } -> GitException(GitException.Kind.NOT_A_REPOSITORY, "This project has no Git repository yet.")
            chain.any { it is CheckoutConflictException } -> GitException(GitException.Kind.UNCOMMITTED_CHANGES,
                "Your uncommitted changes would be overwritten. Commit them first. Nothing was changed.")
            chain.any { it is NotMergedException } -> GitException(GitException.Kind.NOT_MERGED, "That branch has commits that are not merged.")
            chain.any { it is CannotDeleteCurrentBranchException } -> GitException(GitException.Kind.INVALID_INPUT, "You cannot delete the branch you are on.")
            chain.any { it is RefAlreadyExistsException } -> GitException(GitException.Kind.ALREADY_EXISTS, "A branch with that name already exists.")
            chain.any { it is InvalidRefNameException } -> GitException(GitException.Kind.INVALID_INPUT, "That is not a valid branch name.")
            chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException } ->
                GitException(GitException.Kind.NETWORK, "Could not reach the server. Check your internet connection and try again.")
            has("not authorized", "authentication is required", "invalid credentials", "unauthorized", "not permitted", " 401", " 403") ->
                GitException(GitException.Kind.AUTH, "GitHub did not accept the sign-in for this repository. Reconnect GitHub in the app, and make sure the sign-in may read and write this repository.")
            has("repository not found", "not found") && chain.any { it is CoreTransportException || it is ApiTransportException || it is org.eclipse.jgit.api.errors.InvalidRemoteException } ->
                GitException(GitException.Kind.NOT_FOUND, "Repository not found, or your account has no access to it.")
            chain.any { it is CoreTransportException || it is ApiTransportException || it is org.eclipse.jgit.api.errors.InvalidRemoteException } ->
                GitException(GitException.Kind.NETWORK, "The connection to the remote failed. Try again in a moment.")
            has("cancel") -> GitException(GitException.Kind.UNKNOWN, "The operation was cancelled.")
            else -> GitException(GitException.Kind.UNKNOWN, "Git could not finish: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 30
        private const val MAX_SCAN_BYTES = 512 * 1024L
        private const val MAX_COMMIT_FILES = 500
        private const val MAX_COUNT = 10_000

        val DEFAULT_GITIGNORE = """
            # Created by ALMUS APP CREATOR
            build/
            .gradle/
            .idea/
            *.iml
            .DS_Store

            # Never commit secrets or signing keys
            local.properties
            keystore.properties
            *.jks
            *.keystore
            *.p12
            .env
            .env.*
        """.trimIndent() + "\n"
    }
}
