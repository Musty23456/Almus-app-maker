package com.almus.appcreatorpro.git

/**
 * Blocking Git operations on one workspace. Implementations throw [GitException] only. [GitService] calls them on a
 * background thread, one mutating operation at a time per project, and adds validation and confirmations on top.
 */
interface GitEngine {
    fun isRepository(ws: GitWorkspace): Boolean
    fun init(ws: GitWorkspace, defaultBranch: String, createGitignore: Boolean)
    fun clone(ws: GitWorkspace, url: String, branch: String?, ctx: GitContext)

    fun status(ws: GitWorkspace): GitStatus
    fun stage(ws: GitWorkspace, paths: List<String>)
    fun stageAll(ws: GitWorkspace)
    fun unstage(ws: GitWorkspace, paths: List<String>)
    fun unstageAll(ws: GitWorkspace)
    /** Text of the STAGED version of each path (null for binary, deleted or too large files). */
    fun stagedText(ws: GitWorkspace, paths: List<String>): Map<String, String?>
    fun commit(ws: GitWorkspace, message: String, identity: GitIdentity): GitCommit

    fun log(ws: GitWorkspace, skip: Int, limit: Int): List<GitCommit>
    fun commitFiles(ws: GitWorkspace, sha: String): List<GitCommitFile>

    fun branches(ws: GitWorkspace): List<GitBranch>
    fun createBranch(ws: GitWorkspace, name: String, checkout: Boolean)
    fun checkout(ws: GitWorkspace, name: String)
    fun deletePreview(ws: GitWorkspace, name: String): BranchDeletePreview
    fun deleteBranch(ws: GitWorkspace, name: String, force: Boolean)

    fun merge(ws: GitWorkspace, ref: String, label: String, identity: GitIdentity): MergeOutcome
    fun abortMerge(ws: GitWorkspace)
    fun resolveWithSide(ws: GitWorkspace, path: String, side: ConflictSide)

    fun remotes(ws: GitWorkspace): List<GitRemote>
    fun setRemote(ws: GitWorkspace, name: String, url: String)
    fun fetch(ws: GitWorkspace, remote: String, ctx: GitContext): FetchOutcome
    fun commitsToPush(ws: GitWorkspace, remote: String): PushPreview
    fun push(ws: GitWorkspace, remote: String, setUpstream: Boolean, ctx: GitContext): PushOutcome
}
