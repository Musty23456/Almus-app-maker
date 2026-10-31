package com.almus.appcreatorpro.testutil

import com.almus.appcreatorpro.domain.github.AuthenticatedUser
import com.almus.appcreatorpro.domain.github.CommitResult
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubApi
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.GitHubUser
import com.almus.appcreatorpro.domain.github.RepoPage
import com.almus.appcreatorpro.domain.github.WorkflowArtifact
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.github.WorkflowRun
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.service.github.HttpRequest
import com.almus.appcreatorpro.service.github.HttpResponse
import com.almus.appcreatorpro.service.github.HttpTransport

/** Virtual clock: [sleep] just moves time forward, so polling tests run instantly and deterministically. */
class FakeClock(var nowMillis: Long = 1_000_000L) {
    val sleeps = mutableListOf<Long>()
    fun now(): Long = nowMillis
    suspend fun sleep(ms: Long) {
        sleeps += ms
        nowMillis += ms
    }
}

fun jsonResponse(code: Int = 200, body: String = "{}", headers: Map<String, String> = emptyMap()) =
    HttpResponse(code, headers.mapKeys { it.key.lowercase() }, body)

/**
 * Scripted HTTP. `on("GET", "/user", answer1, answer2)` answers matching requests in order (the last answer repeats).
 * An answer is an [HttpResponse] or a [Throwable] (thrown). Unmatched requests fail the test loudly.
 */
class FakeTransport : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    private class Route(val method: String, val contains: String, val answers: List<Any>, var used: Int = 0)
    private val routes = mutableListOf<Route>()

    fun on(method: String, contains: String, vararg answers: Any) {
        routes.add(0, Route(method, contains, answers.toList())) // later registrations win
    }

    fun count(method: String, contains: String) = requests.count { it.method == method && it.url.contains(contains) }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        val route = routes.firstOrNull { it.method == request.method && request.url.contains(it.contains) }
            ?: throw AssertionError("Unexpected request: $request")
        val answer = route.answers[minOf(route.used, route.answers.lastIndex)]
        route.used++
        if (answer is Throwable) throw answer
        return answer as HttpResponse
    }
}

fun repo(
    owner: String = "musty",
    name: String = "builds",
    canPush: Boolean = true,
    defaultBranch: String = "main",
    isPrivate: Boolean = false,
    id: Long = 1L,
) = GitHubRepo(id, owner, name, isPrivate, defaultBranch, canPush, "https://github.com/$owner/$name")

fun run(
    id: Long = 77L,
    status: String = "queued",
    conclusion: String? = null,
    title: String = "Almus cloud build abc123def456",
) = WorkflowRun(id, title, status, conclusion, "https://github.com/musty/builds/actions/runs/$id", "sha1", null)

fun cloudConfig(
    projectId: String = "p1",
    branch: String = "main",
    dir: String = "almus-projects/test-project",
) = CloudBuildConfig(projectId, "musty", "builds", branch, dir)

/** Everything an orchestrator/ViewModel needs from GitHub, scriptable per test. Calls are recorded in [calls]. */
class FakeGitHubApi : GitHubApi {
    val calls = mutableListOf<String>()

    var user: AuthenticatedUser = AuthenticatedUser(GitHubUser("musty"), setOf("repo"))
    var userError: GitHubException? = null
    var repos: List<GitHubRepo> = listOf(repo())
    var repoError: GitHubException? = null
    var repoListError: GitHubException? = null
    var branches: List<GitHubBranch> = listOf(GitHubBranch("main", "sha-main"))
    var branchError: GitHubException? = null
    var commit = CommitSummary("1234567890abcdef", "Initial commit\n\nbody", "Musty", "2026-10-29T10:00:00Z")

    /** path@ref -> text */
    val files = mutableMapOf<String, String>()
    var currentRepo: GitHubRepo = repo()
    var createdRepoError: GitHubException? = null
    val createdRepos = mutableListOf<Triple<String, String?, Boolean>>()
    val createdBranches = mutableListOf<String>()

    var commitError: GitHubException? = null
    var commitResult = CommitResult("commit-sha-1", changed = true)
    var lastCommitFiles: Map<String, String>? = null
    var lastCommitDirectory: String? = null
    var lastCommitBranch: String? = null

    var dispatchError: GitHubException? = null
    var dispatchRunId: Long? = null
    var lastDispatchInputs: Map<String, String>? = null
    var lastDispatchRef: String? = null

    /** Answers for findRun: WorkflowRun? or GitHubException; last one repeats. */
    val findAnswers = ArrayDeque<Any?>()
    /** Answers for getRun: WorkflowRun or GitHubException; last one repeats. */
    val runAnswers = ArrayDeque<Any>()
    var artifacts: List<WorkflowArtifact> = listOf(WorkflowArtifact(1, "almus-apk-abc", 1000, false))
    var artifactsError: GitHubException? = null
    var jobs: List<WorkflowJob> = emptyList()
    var logTail: String = ""
    var logError: GitHubException? = null

    fun installWorkflow(ref: String, text: String = "${CloudBuildConfig.WORKFLOW_MARKER}\nname: x") {
        files["${CloudBuildConfig.WORKFLOW_PATH}@$ref"] = text
    }

    private fun <T> next(queue: ArrayDeque<T>): T = if (queue.size > 1) queue.removeFirst() else queue.first()

    override suspend fun getCurrentUser(): AuthenticatedUser {
        calls += "getCurrentUser"; userError?.let { throw it }; return user
    }

    override suspend fun listRepositories(page: Int, viaInstallations: Boolean): RepoPage {
        calls += "listRepositories:$page:$viaInstallations"; repoListError?.let { throw it }
        return RepoPage(repos, null)
    }

    override suspend fun getRepository(owner: String, name: String): GitHubRepo {
        calls += "getRepository"; repoError?.let { throw it }; return currentRepo
    }

    override suspend fun createRepository(name: String, description: String?, isPrivate: Boolean): GitHubRepo {
        calls += "createRepository"; createdRepoError?.let { throw it }
        createdRepos += Triple(name, description, isPrivate)
        return repo(name = name, isPrivate = isPrivate, id = 99)
    }

    override suspend fun listBranches(owner: String, name: String): List<GitHubBranch> {
        calls += "listBranches"; branchError?.let { throw it }; return branches
    }

    override suspend fun getBranch(owner: String, name: String, branch: String): GitHubBranch {
        calls += "getBranch:$branch"; branchError?.let { throw it }
        return branches.firstOrNull { it.name == branch }
            ?: throw GitHubException(GitHubException.Kind.BRANCH_NOT_FOUND, "Branch \"$branch\" was not found in $owner/$name.")
    }

    override suspend fun getLatestCommit(owner: String, name: String, branch: String): CommitSummary {
        calls += "getLatestCommit"; branchError?.let { throw it }; return commit
    }

    override suspend fun createBranch(owner: String, name: String, newBranch: String, fromSha: String): GitHubBranch {
        calls += "createBranch"; createdBranches += newBranch
        return GitHubBranch(newBranch, fromSha)
    }

    override suspend fun getFileText(owner: String, name: String, path: String, ref: String): String? {
        calls += "getFileText:$ref"; return files["$path@$ref"]
    }

    override suspend fun commitDirectory(
        owner: String, name: String, branch: String, message: String, directory: String, files: Map<String, String>,
    ): CommitResult {
        calls += "commitDirectory"; commitError?.let { throw it }
        lastCommitFiles = files; lastCommitDirectory = directory; lastCommitBranch = branch
        return commitResult
    }

    override suspend fun dispatchWorkflow(owner: String, name: String, workflowFile: String, ref: String, inputs: Map<String, String>): Long? {
        calls += "dispatchWorkflow"; dispatchError?.let { throw it }
        lastDispatchInputs = inputs; lastDispatchRef = ref
        return dispatchRunId
    }

    override suspend fun findRun(owner: String, name: String, workflowFile: String, branch: String, titleMarker: String): WorkflowRun? {
        calls += "findRun"
        val answer = if (findAnswers.isEmpty()) null else next(findAnswers)
        if (answer is GitHubException) throw answer
        return (answer as WorkflowRun?)?.let { it.copy(displayTitle = "Almus cloud build $titleMarker") }
    }

    override suspend fun getRun(owner: String, name: String, runId: Long): WorkflowRun {
        calls += "getRun"
        val answer = next(runAnswers)
        if (answer is GitHubException) throw answer
        return answer as WorkflowRun
    }

    override suspend fun listArtifacts(owner: String, name: String, runId: Long): List<WorkflowArtifact> {
        calls += "listArtifacts"; artifactsError?.let { throw it }; return artifacts
    }

    override suspend fun listJobs(owner: String, name: String, runId: Long): List<WorkflowJob> {
        calls += "listJobs"; return jobs
    }

    override suspend fun getJobLogTail(owner: String, name: String, jobId: Long, maxChars: Int): String {
        calls += "getJobLogTail"; logError?.let { throw it }; return logTail
    }
}

fun ghError(kind: GitHubException.Kind, message: String = "boom", retryAt: Long? = null) =
    GitHubException(kind, message, retryAtMillis = retryAt)

/** A SourceFileRepository with no projects (for tests that only track existing builds). */
object NoFiles : com.almus.appcreatorpro.domain.repository.SourceFileRepository {
    override suspend fun listFiles(projectId: String) = emptyList<com.almus.appcreatorpro.domain.model.SourceFileEntry>()
    override suspend fun readFile(projectId: String, path: String): String = error("no files")
    override suspend fun writeFile(projectId: String, path: String, content: String) = error("no files")
    override suspend fun createFile(projectId: String, path: String, content: String) = error("no files")
    override suspend fun renameFile(projectId: String, fromPath: String, toPath: String) = error("no files")
    override suspend fun deleteFile(projectId: String, path: String) = error("no files")
}
