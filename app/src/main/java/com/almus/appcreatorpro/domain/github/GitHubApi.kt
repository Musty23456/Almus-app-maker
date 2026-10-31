package com.almus.appcreatorpro.domain.github

/** The token's owner plus the OAuth scopes GitHub reports for it (null for GitHub App tokens, which have none). */
data class AuthenticatedUser(val user: GitHubUser, val scopes: Set<String>?)

/**
 * Everything the app needs from GitHub. All calls throw [GitHubException] and nothing else (except
 * CancellationException). Implementations never log or expose the access token.
 */
interface GitHubApi {
    /** Also serves as the "is this token valid?" check. */
    suspend fun getCurrentUser(): AuthenticatedUser

    /** [viaInstallations] = true for GitHub App tokens, which can only see repositories the app is installed on. */
    suspend fun listRepositories(page: Int, viaInstallations: Boolean): RepoPage

    suspend fun getRepository(owner: String, name: String): GitHubRepo

    suspend fun createRepository(name: String, description: String?, isPrivate: Boolean): GitHubRepo

    suspend fun listBranches(owner: String, name: String): List<GitHubBranch>

    suspend fun getBranch(owner: String, name: String, branch: String): GitHubBranch

    suspend fun getLatestCommit(owner: String, name: String, branch: String): CommitSummary

    /** Creates [newBranch] pointing at [fromSha]. */
    suspend fun createBranch(owner: String, name: String, newBranch: String, fromSha: String): GitHubBranch

    /** Text content of a file at [ref], or null if it does not exist there. */
    suspend fun getFileText(owner: String, name: String, path: String, ref: String): String?

    /**
     * Replaces the folder [directory] on [branch] with exactly [files] (relative path -> text) in ONE commit.
     * Fails with CONFLICT if the branch moved while uploading; nothing is changed in that case.
     */
    suspend fun commitDirectory(
        owner: String,
        name: String,
        branch: String,
        message: String,
        directory: String,
        files: Map<String, String>,
    ): CommitResult

    /** Triggers the workflow. Returns the run id only if GitHub reports it (older API behaviour: null). */
    suspend fun dispatchWorkflow(
        owner: String,
        name: String,
        workflowFile: String,
        ref: String,
        inputs: Map<String, String>,
    ): Long?

    /** Finds the run whose title contains [titleMarker], or null if it has not appeared yet. */
    suspend fun findRun(owner: String, name: String, workflowFile: String, branch: String, titleMarker: String): WorkflowRun?

    suspend fun getRun(owner: String, name: String, runId: Long): WorkflowRun

    suspend fun listArtifacts(owner: String, name: String, runId: Long): List<WorkflowArtifact>

    suspend fun listJobs(owner: String, name: String, runId: Long): List<WorkflowJob>

    /** Last [maxChars] characters of a job's log. */
    suspend fun getJobLogTail(owner: String, name: String, jobId: Long, maxChars: Int): String
}
