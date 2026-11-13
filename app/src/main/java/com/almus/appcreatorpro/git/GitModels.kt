package com.almus.appcreatorpro.git

import java.io.File

/** Where a project's repository lives: the work tree is the project's `files/` folder, the Git data sits beside it in `git/`. */
data class GitWorkspace(val workTree: File, val gitDir: File)

enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, UNTRACKED }

/** One path with its state in the index ([staged]) and in the work tree ([unstaged]). A conflicted path has neither. */
data class GitFileChange(
    val path: String,
    val staged: ChangeKind?,
    val unstaged: ChangeKind?,
    val conflicted: Boolean = false,
)

enum class GitRepoState { CLEAN_STATE, MERGING, OTHER }

data class GitStatus(
    /** Current branch, or null when HEAD is detached. */
    val branch: String?,
    val headSha: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
    val changes: List<GitFileChange>,
    val state: GitRepoState,
) {
    val hasCommits: Boolean get() = headSha != null
    val isDetached: Boolean get() = branch == null
    val staged: List<GitFileChange> get() = changes.filter { it.staged != null && !it.conflicted }
    val unstaged: List<GitFileChange> get() = changes.filter { it.unstaged != null && it.unstaged != ChangeKind.UNTRACKED && !it.conflicted }
    val untracked: List<GitFileChange> get() = changes.filter { it.unstaged == ChangeKind.UNTRACKED && !it.conflicted }
    val conflicted: List<GitFileChange> get() = changes.filter { it.conflicted }
    /** True when no tracked file differs from HEAD (untracked files do not count). */
    val hasTrackedChanges: Boolean get() = changes.any { it.conflicted || it.staged != null || (it.unstaged != null && it.unstaged != ChangeKind.UNTRACKED) }
    val isClean: Boolean get() = changes.isEmpty() && state == GitRepoState.CLEAN_STATE
}

data class GitCommit(
    val sha: String,
    val summary: String,
    val message: String,
    val authorName: String,
    val authorEmail: String,
    val timeMillis: Long,
    val parents: List<String>,
) {
    val shortSha: String get() = sha.take(7)
    val isMerge: Boolean get() = parents.size > 1
}

data class GitCommitFile(val path: String, val kind: ChangeKind)

data class GitBranch(
    /** "main" for a local branch, "origin/main" for a remote-tracking one. */
    val name: String,
    val isCurrent: Boolean,
    val isRemote: Boolean,
    val headSha: String?,
    val upstream: String?,
)

data class GitRemote(val name: String, /** Never contains credentials. */ val url: String)

data class GitIdentity(val name: String, val email: String)

enum class MergeStatus { UP_TO_DATE, FAST_FORWARD, MERGED, CONFLICTS }

data class MergeOutcome(val status: MergeStatus, val conflicts: List<String> = emptyList(), val newHead: String? = null)

data class FetchOutcome(val updatedRefs: Int, val remote: String)

data class PushOutcome(val remote: String, val branch: String, val upToDate: Boolean)

data class BranchDeletePreview(
    val name: String,
    val isCurrent: Boolean,
    val isMerged: Boolean,
    /** Commits that exist only on this branch (not reachable from HEAD). */
    val unmergedCommits: Int,
    /** True when every commit of the branch is also on some remote-tracking branch. */
    val existsOnRemote: Boolean,
)

data class PushPreview(val remote: String, val branch: String, val commits: Int?, val createsRemoteBranch: Boolean)

data class ConflictFile(val path: String, val regions: Int, val stillHasMarkers: Boolean)

enum class ConflictSide { OURS, THEIRS }

data class GitProgress(val task: String, val done: Int, val total: Int)

/** What the engine may use for one operation. [isCancelled] is polled by long network operations. */
class GitContext(
    val credentials: GitCredentialSource? = null,
    val identity: GitIdentity? = null,
    val onProgress: (GitProgress) -> Unit = {},
    val isCancelled: () -> Boolean = { false },
)
