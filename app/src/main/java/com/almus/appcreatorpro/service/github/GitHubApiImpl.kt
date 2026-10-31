package com.almus.appcreatorpro.service.github

import com.almus.appcreatorpro.domain.github.AuthenticatedUser
import com.almus.appcreatorpro.domain.github.CommitResult
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubApi
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GitHubException.Kind
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.GitHubUser
import com.almus.appcreatorpro.domain.github.GrantedAccess
import com.almus.appcreatorpro.domain.github.RepoPage
import com.almus.appcreatorpro.domain.github.WorkflowArtifact
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.github.WorkflowRun
import com.almus.appcreatorpro.domain.github.WorkflowStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder

fun interface TokenProvider {
    /** The current access token, or null when not signed in. */
    fun token(): String?
}

/**
 * GitHub REST client. The token is read from [tokenProvider] per request and goes only into the
 * Authorization header; it is never logged, stored in exceptions or put into URLs/bodies.
 *
 * Reads (GET) are retried a few times on network/5xx errors. Writes are never retried automatically,
 * because repeating a dispatch or a commit could duplicate it.
 */
class GitHubApiImpl(
    private val transport: HttpTransport,
    private val tokenProvider: TokenProvider,
    /** Called when GitHub answers 401 so the app can clear the dead token and show "sign in again". */
    private val onUnauthorized: () -> Unit = {},
    private val baseUrl: String = "https://api.github.com",
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxReadRetries: Int = 2,
) : GitHubApi {

    // ---------------------------------------------------------------- user & repositories

    override suspend fun getCurrentUser(): AuthenticatedUser {
        val response = request("GET", "/user")
        val json = parseObject(response)
        val login = json.str("login") ?: throw badResponse("GitHub did not return a user name.")
        val scopesHeader = response.header("x-oauth-scopes")
        return AuthenticatedUser(
            user = GitHubUser(login = login, name = json.str("name")),
            scopes = scopesHeader?.let { GrantedAccess.parseScopes(it) },
        )
    }

    override suspend fun listRepositories(page: Int, viaInstallations: Boolean): RepoPage =
        if (viaInstallations) listInstallationRepositories() else listUserRepositories(page)

    private suspend fun listUserRepositories(page: Int): RepoPage {
        val response = request(
            "GET",
            "/user/repos?per_page=$PAGE_SIZE&page=$page&sort=pushed&affiliation=owner,collaborator,organization_member",
        )
        val items = parseArray(response).mapNotNull { it.asObj()?.let(::parseRepo) }
        val hasNext = response.header("link")?.contains("rel=\"next\"") == true
        return RepoPage(items, if (hasNext) page + 1 else null)
    }

    /** GitHub App user tokens: collect repositories from every installation (capped to keep this bounded). */
    private suspend fun listInstallationRepositories(): RepoPage {
        val installs = parseObject(request("GET", "/user/installations?per_page=100"))
            .arr("installations").orEmpty().mapNotNull { it.asObj()?.long("id") }
        val all = LinkedHashMap<Long, GitHubRepo>()
        for (installationId in installs) {
            var page = 1
            while (page <= MAX_INSTALLATION_PAGES && all.size < MAX_INSTALLATION_REPOS) {
                val response = request("GET", "/user/installations/$installationId/repositories?per_page=100&page=$page")
                val repos = parseObject(response).arr("repositories").orEmpty()
                repos.mapNotNull { it.asObj()?.let(::parseRepo) }.forEach { all[it.id] = it }
                if (repos.size < 100) break
                page++
            }
        }
        return RepoPage(all.values.sortedBy { it.fullName.lowercase() }, nextPage = null)
    }

    override suspend fun getRepository(owner: String, name: String): GitHubRepo {
        val response = request("GET", "/repos/${seg(owner)}/${seg(name)}", notFound = Kind.REPO_NOT_FOUND, notFoundText = "Repository $owner/$name was not found, or this sign-in cannot access it.")
        return parseRepo(parseObject(response)) ?: throw badResponse("GitHub sent incomplete repository data.")
    }

    override suspend fun createRepository(name: String, description: String?, isPrivate: Boolean): GitHubRepo {
        val body = linkedMapOf<String, Any?>("name" to name, "private" to isPrivate, "auto_init" to true)
        if (!description.isNullOrBlank()) body["description"] = description
        val response = request("POST", "/user/repos", body = MiniJson.stringify(body))
        return parseRepo(parseObject(response)) ?: throw badResponse("GitHub sent incomplete repository data.")
    }

    // ---------------------------------------------------------------- branches & commits

    override suspend fun listBranches(owner: String, name: String): List<GitHubBranch> {
        val response = request("GET", "/repos/${seg(owner)}/${seg(name)}/branches?per_page=100", notFound = Kind.REPO_NOT_FOUND, notFoundText = "Repository $owner/$name was not found.")
        return parseArray(response).mapNotNull { item ->
            val obj = item.asObj() ?: return@mapNotNull null
            val branchName = obj.str("name") ?: return@mapNotNull null
            GitHubBranch(branchName, obj.obj("commit")?.str("sha").orEmpty(), obj.bool("protected") ?: false)
        }
    }

    override suspend fun getBranch(owner: String, name: String, branch: String): GitHubBranch {
        val response = request(
            "GET",
            "/repos/${seg(owner)}/${seg(name)}/branches/${path(branch)}",
            notFound = Kind.BRANCH_NOT_FOUND,
            notFoundText = "Branch \"$branch\" was not found in $owner/$name (or the repository is not accessible).",
        )
        val obj = parseObject(response)
        return GitHubBranch(
            name = obj.str("name") ?: branch,
            headSha = obj.obj("commit")?.str("sha") ?: throw badResponse("Branch has no commit."),
            isProtected = obj.bool("protected") ?: false,
        )
    }

    override suspend fun getLatestCommit(owner: String, name: String, branch: String): CommitSummary {
        val response = request(
            "GET",
            "/repos/${seg(owner)}/${seg(name)}/commits/${path(branch)}",
            notFound = Kind.BRANCH_NOT_FOUND,
            notFoundText = "Branch \"$branch\" was not found in $owner/$name.",
        )
        val obj = parseObject(response)
        val commit = obj.obj("commit")
        return CommitSummary(
            sha = obj.str("sha") ?: throw badResponse("Commit has no id."),
            message = commit?.str("message").orEmpty(),
            author = commit?.obj("author")?.str("name"),
            dateIso = commit?.obj("author")?.str("date"),
        )
    }

    override suspend fun createBranch(owner: String, name: String, newBranch: String, fromSha: String): GitHubBranch {
        val body = MiniJson.stringify(mapOf("ref" to "refs/heads/$newBranch", "sha" to fromSha))
        request("POST", "/repos/${seg(owner)}/${seg(name)}/git/refs", body = body, notFound = Kind.REPO_NOT_FOUND)
        return GitHubBranch(newBranch, fromSha)
    }

    override suspend fun getFileText(owner: String, name: String, path: String, ref: String): String? {
        val response = try {
            request("GET", "/repos/${seg(owner)}/${seg(name)}/contents/${path(path)}?ref=${query(ref)}")
        } catch (e: GitHubException) {
            if (e.kind == Kind.NOT_FOUND) return null
            throw e
        }
        val obj = parseObject(response)
        if (obj.str("type") != "file") return null
        val encoded = obj.str("content") ?: return null
        return try {
            String(java.util.Base64.getMimeDecoder().decode(encoded), Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw badResponse("GitHub returned unreadable file content.")
        }
    }

    override suspend fun commitDirectory(
        owner: String,
        name: String,
        branch: String,
        message: String,
        directory: String,
        files: Map<String, String>,
    ): CommitResult {
        val repoPath = "/repos/${seg(owner)}/${seg(name)}"
        val dir = directory.trim('/')

        // 1. Where is the branch now?
        val refJson = parseObject(
            request(
                "GET", "$repoPath/git/ref/heads/${path(branch)}",
                notFound = Kind.BRANCH_NOT_FOUND, notFoundText = "Branch \"$branch\" was not found in $owner/$name.",
            ),
        )
        val parentSha = refJson.obj("object")?.str("sha") ?: throw badResponse("Branch has no commit.")

        // 2. Its tree.
        val parentCommit = parseObject(request("GET", "$repoPath/git/commits/$parentSha"))
        val baseTree = parentCommit.obj("tree")?.str("sha") ?: throw badResponse("Commit has no tree.")

        // 3. Which files already in this folder are no longer wanted?
        val wanted = files.keys.map { "$dir/$it" }.toSet()
        val treeJson = parseObject(request("GET", "$repoPath/git/trees/$baseTree?recursive=1"))
        val truncated = treeJson.bool("truncated") == true
        val toDelete = if (truncated) emptyList() else treeJson.arr("tree").orEmpty().mapNotNull { entry ->
            val e = entry.asObj() ?: return@mapNotNull null
            val p = e.str("path") ?: return@mapNotNull null
            if (e.str("type") == "blob" && p.startsWith("$dir/") && p !in wanted) p else null
        }

        // 4. New tree on top of the old one. Empty files need an explicit blob (the tree API rejects empty content).
        val entries = ArrayList<Map<String, Any?>>()
        for ((relative, content) in files) {
            val entry = linkedMapOf<String, Any?>("path" to "$dir/$relative", "mode" to "100644", "type" to "blob")
            if (content.isEmpty()) {
                val blob = parseObject(
                    request("POST", "$repoPath/git/blobs", body = MiniJson.stringify(mapOf("content" to "", "encoding" to "utf-8"))),
                )
                entry["sha"] = blob.str("sha") ?: throw badResponse("Blob has no id.")
            } else {
                entry["content"] = content
            }
            entries.add(entry)
        }
        for (p in toDelete) entries.add(linkedMapOf("path" to p, "mode" to "100644", "type" to "blob", "sha" to null))

        val newTree = parseObject(
            request("POST", "$repoPath/git/trees", body = MiniJson.stringify(mapOf("base_tree" to baseTree, "tree" to entries))),
        ).str("sha") ?: throw badResponse("Tree has no id.")

        if (newTree == baseTree) {
            return CommitResult(commitSha = parentSha, changed = false, deletionsSkipped = truncated)
        }

        // 5. Commit and 6. move the branch (never forced: if someone else pushed meanwhile, we stop).
        val commitSha = parseObject(
            request(
                "POST", "$repoPath/git/commits",
                body = MiniJson.stringify(mapOf("message" to message, "tree" to newTree, "parents" to listOf(parentSha))),
            ),
        ).str("sha") ?: throw badResponse("Commit has no id.")

        request(
            "PATCH", "$repoPath/git/refs/heads/${path(branch)}",
            body = MiniJson.stringify(mapOf("sha" to commitSha, "force" to false)),
            notFound = Kind.BRANCH_NOT_FOUND,
        )
        return CommitResult(commitSha = commitSha, changed = true, deletionsSkipped = truncated)
    }

    // ---------------------------------------------------------------- workflows

    override suspend fun dispatchWorkflow(
        owner: String,
        name: String,
        workflowFile: String,
        ref: String,
        inputs: Map<String, String>,
    ): Long? {
        val response = request(
            "POST",
            "/repos/${seg(owner)}/${seg(name)}/actions/workflows/${seg(workflowFile)}/dispatches",
            body = MiniJson.stringify(mapOf("ref" to ref, "inputs" to inputs)),
            notFound = Kind.WORKFLOW_NOT_FOUND,
            notFoundText = "Workflow $workflowFile was not found on branch \"$ref\". Add it to the repository first (see Cloud Build > Workflow setup).",
        )
        if (response.body.isBlank()) return null
        return runCatching { parseObject(response).long("workflow_run_id") }.getOrNull()
    }

    override suspend fun findRun(owner: String, name: String, workflowFile: String, branch: String, titleMarker: String): WorkflowRun? {
        val response = request(
            "GET",
            "/repos/${seg(owner)}/${seg(name)}/actions/workflows/${seg(workflowFile)}/runs?event=workflow_dispatch&branch=${query(branch)}&per_page=30",
            notFound = Kind.WORKFLOW_NOT_FOUND,
            notFoundText = "Workflow $workflowFile was not found in $owner/$name.",
        )
        return parseObject(response).arr("workflow_runs").orEmpty()
            .mapNotNull { it.asObj()?.let(::parseRun) }
            .firstOrNull { it.displayTitle.contains(titleMarker) }
    }

    override suspend fun getRun(owner: String, name: String, runId: Long): WorkflowRun {
        val response = request(
            "GET", "/repos/${seg(owner)}/${seg(name)}/actions/runs/$runId",
            notFound = Kind.NOT_FOUND, notFoundText = "Workflow run $runId was not found (it may have been deleted).",
        )
        return parseRun(parseObject(response)) ?: throw badResponse("Workflow run is incomplete.")
    }

    override suspend fun listArtifacts(owner: String, name: String, runId: Long): List<WorkflowArtifact> {
        val response = request("GET", "/repos/${seg(owner)}/${seg(name)}/actions/runs/$runId/artifacts?per_page=30")
        return parseObject(response).arr("artifacts").orEmpty().mapNotNull { item ->
            val o = item.asObj() ?: return@mapNotNull null
            WorkflowArtifact(
                id = o.long("id") ?: return@mapNotNull null,
                name = o.str("name") ?: return@mapNotNull null,
                sizeBytes = o.long("size_in_bytes") ?: 0L,
                expired = o.bool("expired") ?: false,
                expiresAtMillis = o.str("expires_at")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
    }

    override suspend fun listJobs(owner: String, name: String, runId: Long): List<WorkflowJob> {
        val response = request("GET", "/repos/${seg(owner)}/${seg(name)}/actions/runs/$runId/jobs?per_page=30")
        return parseObject(response).arr("jobs").orEmpty().mapNotNull { item ->
            val o = item.asObj() ?: return@mapNotNull null
            WorkflowJob(
                id = o.long("id") ?: return@mapNotNull null,
                name = o.str("name").orEmpty(),
                conclusion = o.str("conclusion"),
                steps = o.arr("steps").orEmpty().mapNotNull { s ->
                    val so = s.asObj() ?: return@mapNotNull null
                    WorkflowStep(so.str("name").orEmpty(), so.str("conclusion"))
                },
            )
        }
    }

    override suspend fun getJobLogTail(owner: String, name: String, jobId: Long, maxChars: Int): String {
        val response = request("GET", "/repos/${seg(owner)}/${seg(name)}/actions/jobs/$jobId/logs")
        return response.body.takeLast(maxChars)
    }

    // ---------------------------------------------------------------- HTTP core

    private suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        notFound: Kind = Kind.NOT_FOUND,
        notFoundText: String? = null,
    ): HttpResponse {
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
            ?: throw GitHubException(Kind.NOT_CONNECTED, "You are not signed in to GitHub.")
        val http = HttpRequest(
            method = method,
            url = baseUrl + path,
            headers = mapOf(
                "Accept" to "application/vnd.github+json",
                "Authorization" to "Bearer $token",
                "X-GitHub-Api-Version" to API_VERSION,
                "User-Agent" to USER_AGENT,
            ),
            body = body,
        )
        val retries = if (method == "GET") maxReadRetries else 0
        var attempt = 0
        while (true) {
            val failure: GitHubException = try {
                val response = transport.execute(http)
                if (response.isSuccess) return response
                mapError(response, notFound, notFoundText)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException) {
                throw e
            } catch (e: SocketTimeoutException) {
                GitHubException(Kind.TIMEOUT, "GitHub took too long to answer. Check your connection and try again.", cause = e)
            } catch (e: IOException) {
                GitHubException(Kind.NETWORK, "Could not reach GitHub. Check your internet connection.", cause = e)
            }
            if (failure.kind == Kind.TOKEN_REJECTED) onUnauthorized()
            if (!failure.isTransient || attempt >= retries) throw failure
            attempt++
            sleep(RETRY_BASE_MS * attempt)
        }
    }

    private fun mapError(response: HttpResponse, notFound: Kind, notFoundText: String?): GitHubException {
        val code = response.code
        val apiMessage = runCatching { MiniJson.parseObject(response.body).str("message") }.getOrNull()
        val remaining = response.header("x-ratelimit-remaining")
        val retryAfter = response.header("retry-after")?.toLongOrNull()
        val looksRateLimited = (code == 403 || code == 429) && (
            remaining == "0" || retryAfter != null || apiMessage?.contains("rate limit", ignoreCase = true) == true
            )
        return when {
            code == 401 -> GitHubException(
                Kind.TOKEN_REJECTED,
                "GitHub rejected the saved sign-in (expired or revoked). Please connect again.",
                httpStatus = code,
            )
            looksRateLimited -> {
                val resetAt = response.header("x-ratelimit-reset")?.toLongOrNull()?.times(1000)
                    ?: retryAfter?.let { now() + it * 1000 }
                GitHubException(
                    Kind.RATE_LIMITED,
                    "GitHub rate limit reached." + (resetAt?.let { " Try again in about ${minutesUntil(it)} min." } ?: " Try again later."),
                    retryAtMillis = resetAt,
                    httpStatus = code,
                )
            }
            code == 403 -> GitHubException(
                Kind.FORBIDDEN,
                "GitHub refused this action: ${apiMessage ?: "permission denied"}. " +
                    "Check that your sign-in has access to this repository and that the branch is not protected.",
                httpStatus = code,
            )
            code == 404 -> GitHubException(
                notFound,
                notFoundText ?: "GitHub could not find that (it may not exist, or your sign-in cannot access it).",
                httpStatus = code,
            )
            code == 409 || (code == 422 && apiMessage?.contains("fast forward", ignoreCase = true) == true) ->
                GitHubException(Kind.CONFLICT, "The branch changed on GitHub while uploading. Nothing was changed; try again.", httpStatus = code)
            code == 422 -> GitHubException(Kind.VALIDATION, "GitHub rejected the request: ${apiMessage ?: "invalid input"}.", httpStatus = code)
            code in 500..599 -> GitHubException(Kind.SERVER, "GitHub is having problems (HTTP $code). Try again in a moment.", httpStatus = code)
            else -> GitHubException(Kind.UNKNOWN, "Unexpected response from GitHub (HTTP $code).", httpStatus = code)
        }
    }

    private fun minutesUntil(epochMillis: Long): Long = ((epochMillis - now()).coerceAtLeast(0L) + 59_999L) / 60_000L

    private fun parseObject(response: HttpResponse): Map<String, Any?> = try {
        MiniJson.parseObject(response.body)
    } catch (e: MiniJson.JsonException) {
        throw badResponse("GitHub sent a response the app could not read.")
    }

    private fun parseArray(response: HttpResponse): List<Any?> = try {
        MiniJson.parse(response.body).asArr() ?: throw badResponse("GitHub sent an unexpected response.")
    } catch (e: MiniJson.JsonException) {
        throw badResponse("GitHub sent a response the app could not read.")
    }

    private fun badResponse(text: String) = GitHubException(Kind.BAD_RESPONSE, text)

    private fun parseRepo(o: Map<String, Any?>): GitHubRepo? {
        val ownerLogin = o.obj("owner")?.str("login") ?: return null
        val repoName = o.str("name") ?: return null
        return GitHubRepo(
            id = o.long("id") ?: return null,
            owner = ownerLogin,
            name = repoName,
            isPrivate = o.bool("private") ?: false,
            defaultBranch = o.str("default_branch") ?: "main",
            canPush = o.obj("permissions")?.bool("push") ?: true,
            htmlUrl = o.str("html_url") ?: "https://github.com/$ownerLogin/$repoName",
            description = o.str("description"),
        )
    }

    private fun parseRun(o: Map<String, Any?>): WorkflowRun? {
        val id = o.long("id") ?: return null
        return WorkflowRun(
            id = id,
            displayTitle = o.str("display_title") ?: o.str("name").orEmpty(),
            status = o.str("status").orEmpty(),
            conclusion = o.str("conclusion"),
            htmlUrl = o.str("html_url").orEmpty(),
            headSha = o.str("head_sha"),
            createdAtIso = o.str("created_at"),
        )
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun seg(s: String) = encode(s)
    private fun path(s: String) = s.split('/').joinToString("/") { encode(it) }
    private fun query(s: String) = encode(s)

    private companion object {
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "AlmusAppCreatorPro"
        const val PAGE_SIZE = 30
        const val RETRY_BASE_MS = 1_000L
        const val MAX_INSTALLATION_PAGES = 3
        const val MAX_INSTALLATION_REPOS = 300
    }
}
