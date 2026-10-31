package com.almus.appcreatorpro.github

import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GitHubException.Kind
import com.almus.appcreatorpro.service.github.GitHubApiImpl
import com.almus.appcreatorpro.service.github.MiniJson
import com.almus.appcreatorpro.service.github.arr
import com.almus.appcreatorpro.service.github.asObj
import com.almus.appcreatorpro.service.github.obj
import com.almus.appcreatorpro.service.github.str
import com.almus.appcreatorpro.testutil.FakeClock
import com.almus.appcreatorpro.testutil.FakeTransport
import com.almus.appcreatorpro.testutil.jsonResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

private const val TOKEN = "gho_SECRET_TOKEN_1234567890abcdefghijkl"

class GitHubApiImplTest {
    private val clock = FakeClock(nowMillis = 1_000_000_000L)
    private val transport = FakeTransport()
    private var rejected = 0

    private fun api(token: String? = TOKEN, retries: Int = 2) = GitHubApiImpl(
        transport = transport,
        tokenProvider = { token },
        onUnauthorized = { rejected++ },
        now = clock::now,
        sleep = clock::sleep,
        maxReadRetries = retries,
    )

    private suspend fun failure(block: suspend () -> Unit): GitHubException {
        try {
            block()
        } catch (e: GitHubException) {
            return e
        }
        throw AssertionError("expected a GitHubException")
    }

    // ---------------------------------------------------------------- unauthenticated

    @Test
    fun without_a_token_nothing_is_sent_and_the_error_says_not_connected() = runTest {
        val e = failure { api(token = null).getCurrentUser() }
        assertEquals(Kind.NOT_CONNECTED, e.kind)
        assertTrue(e.isAuthProblem)
        assertTrue(transport.requests.isEmpty())
        assertEquals(Kind.NOT_CONNECTED, failure { api(token = "  ").listBranches("o", "r") }.kind)
    }

    @Test
    fun a_401_means_token_rejected_calls_the_callback_and_is_not_retried() = runTest {
        transport.on("GET", "/user", jsonResponse(401, """{"message":"Bad credentials"}"""))
        val e = failure { api().getCurrentUser() }
        assertEquals(Kind.TOKEN_REJECTED, e.kind)
        assertEquals(1, rejected)
        assertEquals(1, transport.requests.size)
        assertTrue(clock.sleeps.isEmpty())
    }

    // ---------------------------------------------------------------- token hygiene

    @Test
    fun token_is_only_in_the_authorization_header() = runTest {
        transport.on("GET", "/user", jsonResponse(200, """{"login":"musty","name":"Musty"}""", mapOf("X-OAuth-Scopes" to "repo, read:org")))
        val me = api().getCurrentUser()
        assertEquals("musty", me.user.login)
        assertEquals(setOf("repo", "read:org"), me.scopes)
        val request = transport.requests.single()
        assertEquals("Bearer $TOKEN", request.headers["Authorization"])
        assertFalse(request.url.contains(TOKEN))
        assertFalse(request.toString().contains(TOKEN))
        assertNull(request.body)
    }

    @Test
    fun github_app_tokens_report_no_scopes() = runTest {
        transport.on("GET", "/user", jsonResponse(200, """{"login":"musty"}"""))
        assertNull(api().getCurrentUser().scopes)
    }

    @Test
    fun error_messages_never_contain_the_token() = runTest {
        transport.on("GET", "/repos/o/r", jsonResponse(403, """{"message":"Resource not accessible $TOKEN"}"""))
        val e = failure { api().getRepository("o", "r") }
        // The API message is shown, but the app's own wording must not add the token, and callers never pass it in.
        assertFalse(transport.requests.single().url.contains(TOKEN))
        assertNotNull(e.message)
    }

    // ---------------------------------------------------------------- error mapping

    @Test
    fun primary_rate_limit_reports_when_it_resets() = runTest {
        val resetEpochSeconds = clock.nowMillis / 1000 + 600
        transport.on(
            "GET", "/user/repos",
            jsonResponse(403, """{"message":"API rate limit exceeded"}""", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "$resetEpochSeconds")),
        )
        val e = failure { api().listRepositories(1, viaInstallations = false) }
        assertEquals(Kind.RATE_LIMITED, e.kind)
        assertEquals(resetEpochSeconds * 1000, e.retryAtMillis)
        assertTrue(e.message!!.contains("10 min"))
        assertEquals("rate limits are not retried automatically", 1, transport.requests.size)
    }

    @Test
    fun secondary_rate_limit_uses_retry_after() = runTest {
        transport.on("GET", "/branches", jsonResponse(429, "{}", mapOf("retry-after" to "30")))
        val e = failure { api().listBranches("o", "r") }
        assertEquals(Kind.RATE_LIMITED, e.kind)
        assertEquals(clock.nowMillis + 30_000, e.retryAtMillis)
    }

    @Test
    fun plain_403_is_forbidden_not_rate_limited() = runTest {
        transport.on("POST", "/git/refs", jsonResponse(403, """{"message":"Resource not accessible by integration"}""", mapOf("x-ratelimit-remaining" to "4000")))
        val e = failure { api().createBranch("o", "r", "x", "sha") }
        assertEquals(Kind.FORBIDDEN, e.kind)
        assertTrue(e.message!!.contains("Resource not accessible by integration"))
    }

    @Test
    fun invalid_repository_and_invalid_branch_are_distinguished() = runTest {
        transport.on("GET", "/repos/musty/nope", jsonResponse(404, """{"message":"Not Found"}"""))
        transport.on("GET", "/branches/ghost", jsonResponse(404, """{"message":"Branch not found"}"""))
        val repoError = failure { api().getRepository("musty", "nope") }
        assertEquals(Kind.REPO_NOT_FOUND, repoError.kind)
        assertTrue(repoError.message!!.contains("musty/nope"))
        val branchError = failure { api().getBranch("musty", "nope", "ghost") }
        assertEquals(Kind.BRANCH_NOT_FOUND, branchError.kind)
        assertTrue(branchError.message!!.contains("ghost"))
    }

    @Test
    fun branch_names_with_slashes_and_spaces_are_encoded_safely() = runTest {
        transport.on("GET", "/branches/", jsonResponse(200, """{"name":"feature/a b","commit":{"sha":"s"},"protected":true}"""))
        val branch = api().getBranch("o", "r", "feature/a b")
        assertTrue(branch.isProtected)
        assertTrue(transport.requests.single().url.endsWith("/branches/feature/a%20b"))
    }

    @Test
    fun transient_failures_are_retried_for_reads_then_succeed() = runTest {
        transport.on("GET", "/user", jsonResponse(502), IOException("reset"), jsonResponse(200, """{"login":"musty"}"""))
        assertEquals("musty", api().getCurrentUser().user.login)
        assertEquals(3, transport.requests.size)
        assertEquals(listOf(1_000L, 2_000L), clock.sleeps)
    }

    @Test
    fun reads_give_up_after_the_retry_limit_with_the_right_kind() = runTest {
        transport.on("GET", "/user", jsonResponse(503))
        assertEquals(Kind.SERVER, failure { api(retries = 2).getCurrentUser() }.kind)
        assertEquals(3, transport.requests.size)

        val t2 = FakeTransport().apply { on("GET", "/user", IOException("offline")) }
        val offline = GitHubApiImpl(t2, { TOKEN }, sleep = clock::sleep)
        assertEquals(Kind.NETWORK, failure { offline.getCurrentUser() }.kind)

        val t3 = FakeTransport().apply { on("GET", "/user", SocketTimeoutException("slow")) }
        val slow = GitHubApiImpl(t3, { TOKEN }, sleep = clock::sleep, maxReadRetries = 0)
        assertEquals(Kind.TIMEOUT, failure { slow.getCurrentUser() }.kind)
    }

    @Test
    fun writes_are_never_retried() = runTest {
        transport.on("POST", "/dispatches", jsonResponse(500))
        assertEquals(Kind.SERVER, failure { api().dispatchWorkflow("o", "r", "w.yml", "main", emptyMap()) }.kind)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun unreadable_response_is_a_bad_response_error() = runTest {
        transport.on("GET", "/user", jsonResponse(200, "<html>not json</html>"))
        assertEquals(Kind.BAD_RESPONSE, failure { api().getCurrentUser() }.kind)
        val t = FakeTransport().apply { on("GET", "/user", jsonResponse(200, """{"nologin":1}""")) }
        assertEquals(Kind.BAD_RESPONSE, failure { GitHubApiImpl(t, { TOKEN }).getCurrentUser() }.kind)
    }

    // ---------------------------------------------------------------- repositories

    private fun repoJson(name: String, id: Long = 1, push: Boolean = true) =
        """{"id":$id,"name":"$name","private":false,"default_branch":"main","html_url":"https://github.com/musty/$name","owner":{"login":"musty"},"permissions":{"push":$push}}"""

    @Test
    fun lists_repositories_with_paging_and_permissions() = runTest {
        transport.on(
            "GET", "/user/repos",
            jsonResponse(200, "[${repoJson("a", 1)},${repoJson("b", 2, push = false)}]", mapOf("link" to "<https://api.github.com/user/repos?page=2>; rel=\"next\"")),
        )
        val page = api().listRepositories(1, viaInstallations = false)
        assertEquals(listOf("musty/a", "musty/b"), page.items.map { it.fullName })
        assertEquals(listOf(true, false), page.items.map { it.canPush })
        assertEquals(2, page.nextPage)
    }

    @Test
    fun last_page_has_no_next_page() = runTest {
        transport.on("GET", "/user/repos", jsonResponse(200, "[${repoJson("a")}]"))
        assertNull(api().listRepositories(3, viaInstallations = false).nextPage)
    }

    @Test
    fun github_app_tokens_list_repositories_through_installations() = runTest {
        transport.on("GET", "/user/installations?", jsonResponse(200, """{"installations":[{"id":10},{"id":11}]}"""))
        transport.on("GET", "/user/installations/10/repositories", jsonResponse(200, """{"repositories":[${repoJson("zeta", 1)}]}"""))
        transport.on("GET", "/user/installations/11/repositories", jsonResponse(200, """{"repositories":[${repoJson("alpha", 2)}]}"""))
        val page = api().listRepositories(1, viaInstallations = true)
        assertEquals(listOf("musty/alpha", "musty/zeta"), page.items.map { it.fullName })
        assertNull(page.nextPage)
    }

    @Test
    fun repository_without_push_info_defaults_to_pushable_and_skips_broken_entries() = runTest {
        transport.on("GET", "/user/repos", jsonResponse(200, """[{"id":1,"name":"a","owner":{"login":"musty"}},{"broken":true}]"""))
        val items = api().listRepositories(1, false).items
        assertEquals(1, items.size)
        assertTrue(items.single().canPush)
        assertEquals("main", items.single().defaultBranch)
    }

    @Test
    fun create_repository_sends_the_requested_visibility() = runTest {
        transport.on("POST", "/user/repos", jsonResponse(201, repoJson("fresh", 5)))
        val created = api().createRepository("fresh", "desc", isPrivate = true)
        assertEquals("musty/fresh", created.fullName)
        val body = MiniJson.parseObject(transport.requests.single().body!!)
        assertEquals(true, body["private"])
        assertEquals("fresh", body.str("name"))
    }

    @Test
    fun create_repository_name_taken_is_a_validation_error() = runTest {
        transport.on("POST", "/user/repos", jsonResponse(422, """{"message":"Repository creation failed."}"""))
        val e = failure { api().createRepository("dup", null, false) }
        assertEquals(Kind.VALIDATION, e.kind)
    }

    @Test
    fun latest_commit_and_branch_list() = runTest {
        transport.on("GET", "/commits/main", jsonResponse(200, """{"sha":"abcdef1234567","commit":{"message":"Fix\n\nlong","author":{"name":"Musty","date":"2026-10-29T10:00:00Z"}}}"""))
        val commit = api().getLatestCommit("o", "r", "main")
        assertEquals("abcdef1", commit.shortSha)
        assertEquals("Fix", commit.title)
        assertEquals("Musty", commit.author)

        transport.on("GET", "/branches?", jsonResponse(200, """[{"name":"main","commit":{"sha":"1"},"protected":false},{"name":"dev","commit":{"sha":"2"},"protected":true}]"""))
        val branches = api().listBranches("o", "r")
        assertEquals(listOf("main", "dev"), branches.map { it.name })
        assertTrue(branches[1].isProtected)
    }

    @Test
    fun file_text_is_decoded_and_missing_files_are_null() = runTest {
        val encoded = java.util.Base64.getMimeEncoder().encodeToString("# marker\nname: x".toByteArray())
        transport.on("GET", "/contents/.github/workflows/w.yml", jsonResponse(200, """{"type":"file","content":${MiniJson.stringify(encoded)}}"""))
        transport.on("GET", "/contents/missing", jsonResponse(404, """{"message":"Not Found"}"""))
        assertEquals("# marker\nname: x", api().getFileText("o", "r", ".github/workflows/w.yml", "main"))
        assertNull(api().getFileText("o", "r", "missing.txt", "main"))
    }

    // ---------------------------------------------------------------- commit

    private fun commitRoutes(newTreeSha: String = "tree-new", existing: String = "[]", truncated: Boolean = false) {
        transport.on("GET", "/git/ref/heads/main", jsonResponse(200, """{"object":{"sha":"parent-sha"}}"""))
        transport.on("GET", "/git/commits/parent-sha", jsonResponse(200, """{"tree":{"sha":"tree-old"}}"""))
        transport.on("GET", "/git/trees/tree-old?recursive=1", jsonResponse(200, """{"truncated":$truncated,"tree":$existing}"""))
        transport.on("POST", "/git/trees", jsonResponse(201, """{"sha":"$newTreeSha"}"""))
        transport.on("POST", "/git/commits", jsonResponse(201, """{"sha":"commit-new"}"""))
        transport.on("PATCH", "/git/refs/heads/main", jsonResponse(200, "{}"))
        transport.on("POST", "/git/blobs", jsonResponse(201, """{"sha":"empty-blob"}"""))
    }

    @Test
    fun commit_uploads_files_deletes_stale_ones_and_moves_the_branch_without_force() = runTest {
        commitRoutes(
            existing = """[
              {"path":"almus-projects/app/old.kt","type":"blob"},
              {"path":"almus-projects/app/keep.kt","type":"blob"},
              {"path":"almus-projects/other/x.kt","type":"blob"},
              {"path":"almus-projects/app","type":"tree"},
              {"path":"README.md","type":"blob"}]""",
        )
        val result = api().commitDirectory("o", "r", "main", "msg", "almus-projects/app/", mapOf("keep.kt" to "fun a() {}", "new.kt" to "x", "empty.txt" to ""))
        assertTrue(result.changed)
        assertEquals("commit-new", result.commitSha)

        val tree = MiniJson.parseObject(transport.requests.first { it.url.endsWith("/git/trees") }.body!!)
        assertEquals("tree-old", tree.str("base_tree"))
        val entries = tree.arr("tree")!!.map { it.asObj()!! }
        assertEquals(
            setOf("almus-projects/app/keep.kt", "almus-projects/app/new.kt", "almus-projects/app/empty.txt", "almus-projects/app/old.kt"),
            entries.map { it.str("path")!! }.toSet(),
        )
        val deleted = entries.single { it.str("path") == "almus-projects/app/old.kt" }
        assertTrue("deletion is an explicit null sha", deleted.containsKey("sha") && deleted["sha"] == null)
        assertEquals("empty-blob", entries.single { it.str("path")!!.endsWith("empty.txt") }.str("sha"))
        assertEquals("fun a() {}", entries.single { it.str("path")!!.endsWith("keep.kt") }.str("content"))
        // Files outside the project folder must be untouched.
        assertTrue(entries.none { it.str("path")!!.contains("other/") || it.str("path") == "README.md" })

        val commit = MiniJson.parseObject(transport.requests.first { it.url.endsWith("/git/commits") }.body!!)
        assertEquals(listOf<Any?>("parent-sha"), commit["parents"])
        val patch = MiniJson.parseObject(transport.requests.first { it.method == "PATCH" }.body!!)
        assertEquals(false, patch["force"])
        assertEquals("commit-new", patch.str("sha"))
        assertTrue(transport.requests.none { it.body?.contains(TOKEN) == true || it.url.contains(TOKEN) })
    }

    @Test
    fun identical_files_create_no_commit() = runTest {
        commitRoutes(newTreeSha = "tree-old")
        val result = api().commitDirectory("o", "r", "main", "msg", "d", mapOf("a.kt" to "x"))
        assertFalse(result.changed)
        assertEquals("parent-sha", result.commitSha)
        assertEquals(0, transport.count("POST", "/git/commits"))
        assertEquals(0, transport.count("PATCH", "/git/refs"))
    }

    @Test
    fun truncated_tree_skips_deletions_and_says_so() = runTest {
        commitRoutes(existing = """[{"path":"d/old.kt","type":"blob"}]""", truncated = true)
        val result = api().commitDirectory("o", "r", "main", "msg", "d", mapOf("a.kt" to "x"))
        assertTrue(result.deletionsSkipped)
        val entries = MiniJson.parseObject(transport.requests.first { it.url.endsWith("/git/trees") }.body!!).arr("tree")!!
        assertEquals(1, entries.size)
    }

    @Test
    fun branch_that_moved_during_upload_is_a_conflict_and_nothing_is_forced() = runTest {
        commitRoutes()
        transport.on("PATCH", "/git/refs/heads/main", jsonResponse(422, """{"message":"Update is not a fast forward"}"""))
        val e = failure { api().commitDirectory("o", "r", "main", "msg", "d", mapOf("a.kt" to "x")) }
        assertEquals(Kind.CONFLICT, e.kind)
        assertEquals(1, transport.count("PATCH", "/git/refs"))
    }

    @Test
    fun committing_to_a_missing_branch_or_protected_branch_fails_clearly() = runTest {
        transport.on("GET", "/git/ref/heads/ghost", jsonResponse(404, """{"message":"Not Found"}"""))
        assertEquals(Kind.BRANCH_NOT_FOUND, failure { api().commitDirectory("o", "r", "ghost", "m", "d", mapOf("a" to "b")) }.kind)

        commitRoutes()
        transport.on("PATCH", "/git/refs/heads/main", jsonResponse(403, """{"message":"Protected branch update failed"}"""))
        val e = failure { api().commitDirectory("o", "r", "main", "msg", "d", mapOf("a.kt" to "x")) }
        assertEquals(Kind.FORBIDDEN, e.kind)
        assertTrue(e.message!!.contains("protected", ignoreCase = true))
    }

    // ---------------------------------------------------------------- workflow dispatch and runs

    @Test
    fun dispatch_sends_ref_and_inputs_and_returns_no_run_id_on_204() = runTest {
        transport.on("POST", "/actions/workflows/almus-cloud-build.yml/dispatches", jsonResponse(204, ""))
        val id = api().dispatchWorkflow("o", "r", "almus-cloud-build.yml", "main", mapOf("build_id" to "abc"))
        assertNull(id)
        val body = MiniJson.parseObject(transport.requests.single().body!!)
        assertEquals("main", body.str("ref"))
        assertEquals("abc", body.obj("inputs")!!.str("build_id"))
    }

    @Test
    fun dispatch_uses_the_run_id_when_github_returns_one() = runTest {
        transport.on("POST", "/dispatches", jsonResponse(200, """{"workflow_run_id": 4242}"""))
        assertEquals(4242L, api().dispatchWorkflow("o", "r", "w.yml", "main", emptyMap()))
    }

    @Test
    fun dispatch_errors_are_explained() = runTest {
        transport.on("POST", "/workflows/missing.yml/dispatches", jsonResponse(404, """{"message":"Not Found"}"""))
        val notFound = failure { api().dispatchWorkflow("o", "r", "missing.yml", "dev", emptyMap()) }
        assertEquals(Kind.WORKFLOW_NOT_FOUND, notFound.kind)
        assertTrue(notFound.message!!.contains("\"dev\""))

        transport.on("POST", "/workflows/nodispatch.yml/dispatches", jsonResponse(422, """{"message":"Workflow does not have 'workflow_dispatch' trigger"}"""))
        val invalid = failure { api().dispatchWorkflow("o", "r", "nodispatch.yml", "main", emptyMap()) }
        assertEquals(Kind.VALIDATION, invalid.kind)
        assertTrue(invalid.message!!.contains("workflow_dispatch"))

        transport.on("POST", "/workflows/denied.yml/dispatches", jsonResponse(403, """{"message":"Resource not accessible by integration"}"""))
        assertEquals(Kind.FORBIDDEN, failure { api().dispatchWorkflow("o", "r", "denied.yml", "main", emptyMap()) }.kind)
    }

    @Test
    fun finds_the_run_by_its_unique_title() = runTest {
        transport.on(
            "GET", "/actions/workflows/w.yml/runs",
            jsonResponse(
                200,
                """{"workflow_runs":[
                  {"id":1,"display_title":"Almus cloud build 111111111111","status":"completed","conclusion":"success","html_url":"u1"},
                  {"id":2,"display_title":"Almus cloud build 222222222222","status":"in_progress","html_url":"u2"}]}""",
            ),
        )
        val run = api().findRun("o", "r", "w.yml", "main", "222222222222")
        assertEquals(2L, run!!.id)
        assertEquals("in_progress", run.status)
        assertNull(api().findRun("o", "r", "w.yml", "main", "999999999999"))
        assertTrue(transport.requests.first().url.contains("event=workflow_dispatch"))
    }

    @Test
    fun run_artifacts_and_jobs_are_parsed() = runTest {
        transport.on("GET", "/actions/runs/7", jsonResponse(200, """{"id":7,"status":"completed","conclusion":"success","html_url":"u"}"""))
        transport.on("GET", "/actions/runs/7/artifacts", jsonResponse(200, """{"artifacts":[{"id":1,"name":"almus-apk-x","size_in_bytes":2048,"expired":false}]}"""))
        transport.on(
            "GET", "/actions/runs/7/jobs",
            jsonResponse(200, """{"jobs":[{"id":5,"name":"build","conclusion":"failure","steps":[{"name":"Build debug APK","conclusion":"failure"},{"name":"x","conclusion":"success"}]}]}"""),
        )
        assertEquals("almus-apk-x", api().listArtifacts("o", "r", 7).single().name)
        assertEquals("Build debug APK", api().listJobs("o", "r", 7).single().steps.first().name)
        assertEquals(com.almus.appcreatorpro.domain.model.BuildStatus.SUCCESS, api().getRun("o", "r", 7).mappedStatus)
    }

    @Test
    fun deleted_run_is_not_found() = runTest {
        transport.on("GET", "/actions/runs/9", jsonResponse(404, "{}"))
        assertEquals(Kind.NOT_FOUND, failure { api().getRun("o", "r", 9) }.kind)
    }

    @Test
    fun job_log_tail_is_bounded() = runTest {
        transport.on("GET", "/actions/jobs/5/logs", jsonResponse(200, "x".repeat(500) + "END"))
        val tail = api().getJobLogTail("o", "r", 5, 100)
        assertEquals(100, tail.length)
        assertTrue(tail.endsWith("END"))
    }
}
