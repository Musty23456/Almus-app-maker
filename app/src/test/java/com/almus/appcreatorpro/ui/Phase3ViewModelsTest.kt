package com.almus.appcreatorpro.ui

import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.data.repository.InMemoryBuildStore
import com.almus.appcreatorpro.data.repository.InMemoryProjectRepository
import com.almus.appcreatorpro.data.repository.SampleBuildRepository
import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.github.AuthMethod
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubException.Kind
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.service.build.BuildTracker
import com.almus.appcreatorpro.service.build.CloudBuildOrchestrator
import com.almus.appcreatorpro.service.github.DeviceFlowClient
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.GitHubAuthState
import com.almus.appcreatorpro.service.github.InMemorySecretStore
import com.almus.appcreatorpro.testutil.FakeClock
import com.almus.appcreatorpro.testutil.FakeGitHubApi
import com.almus.appcreatorpro.testutil.FakeTransport
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.testutil.NoFiles
import com.almus.appcreatorpro.testutil.ghError
import com.almus.appcreatorpro.testutil.jsonResponse
import com.almus.appcreatorpro.testutil.repo
import com.almus.appcreatorpro.testutil.testProject
import com.almus.appcreatorpro.ui.builds.BuildHistoryViewModel
import com.almus.appcreatorpro.ui.builds.BuildStatusState
import com.almus.appcreatorpro.ui.builds.BuildStatusViewModel
import com.almus.appcreatorpro.ui.cloudbuild.CloudBuildViewModel
import com.almus.appcreatorpro.ui.github.GitHubConnectionViewModel
import com.almus.appcreatorpro.ui.github.RepositorySelectorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val CLIENT_ID = "Iv1.abcdef1234567890"
private const val TOKEN = "gho_SECRET_TOKEN_1234567890abcdefghijkl"

/** Auth manager wired to a fake transport; the manager's own api is replaced via the returned fake where needed. */
private class AuthFixture(connected: Boolean = true, method: AuthMethod = AuthMethod.OAUTH_PRIVATE, scopes: String = "repo") {
    val transport = FakeTransport()
    val meta = InMemoryKeyValueStore()
    val secrets = InMemorySecretStore()
    val clock = FakeClock()
    val manager: GitHubAuthManager

    init {
        if (connected) {
            secrets.put("github_access_token", TOKEN)
            meta.putString("github_username", "musty")
            meta.putString("github_auth_method", method.id)
            meta.putString("github_scopes", scopes)
            transport.on("GET", "/user", jsonResponse(200, """{"login":"musty"}""", mapOf("x-oauth-scopes" to scopes)))
        }
        manager = GitHubAuthManager(meta, secrets, transport, DeviceFlowClient(transport, clock::now, clock::sleep), clock::now)
    }
}

class GitHubConnectionViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun not_connected_by_default_and_connect_needs_a_valid_client_id() {
        val f = AuthFixture(connected = false)
        val vm = GitHubConnectionViewModel(f.manager)
        assertEquals(GitHubAuthState.Disconnected, vm.authState.value)

        vm.connect()
        assertNotNull(vm.form.value.clientIdError)
        assertTrue("no network call without a client id", f.transport.requests.isEmpty())

        vm.onClientIdChange("bad id")
        vm.connect()
        assertNotNull(vm.form.value.clientIdError)
        assertTrue(f.transport.requests.isEmpty())
    }

    @Test
    fun a_saved_sign_in_is_verified_on_open() {
        val f = AuthFixture()
        val vm = GitHubConnectionViewModel(f.manager)
        assertTrue(vm.authState.value is GitHubAuthState.Connected)
    }

    @Test
    fun expired_saved_sign_in_shows_expired_and_removes_the_token() {
        val f = AuthFixture()
        f.transport.on("GET", "/user", jsonResponse(401, "{}"))
        val vm = GitHubConnectionViewModel(f.manager)
        assertTrue(vm.authState.value is GitHubAuthState.Expired)
        assertNull(f.secrets.get("github_access_token"))
    }

    @Test
    fun full_sign_in_and_disconnect() {
        val f = AuthFixture(connected = false)
        f.transport.on("POST", "/login/device/code", jsonResponse(200, """{"device_code":"D","user_code":"AAAA-1111","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""))
        f.transport.on("POST", "/login/oauth/access_token", jsonResponse(200, """{"access_token":"$TOKEN","scope":"public_repo"}"""))
        f.transport.on("GET", "/user", jsonResponse(200, """{"login":"musty"}""", mapOf("x-oauth-scopes" to "public_repo")))
        val vm = GitHubConnectionViewModel(f.manager)
        vm.onMethodChange(AuthMethod.OAUTH_PUBLIC)
        vm.onClientIdChange(CLIENT_ID)
        vm.connect()
        assertTrue(vm.authState.value is GitHubAuthState.Connected)

        vm.disconnect()
        assertEquals(GitHubAuthState.Disconnected, vm.authState.value)
        assertNull(f.secrets.get("github_access_token"))
        assertNotNull(vm.message.value)
        vm.consumeMessage()
        assertNull(vm.message.value)
    }

    @Test
    fun form_remembers_the_saved_client_id_and_method() {
        val f = AuthFixture(connected = false)
        f.meta.putString("github_client_id", CLIENT_ID)
        f.meta.putString("github_auth_method", AuthMethod.GITHUB_APP.id)
        val vm = GitHubConnectionViewModel(f.manager)
        assertEquals(CLIENT_ID, vm.form.value.clientId)
        assertEquals(AuthMethod.GITHUB_APP, vm.form.value.method)
    }
}

/** Uses a fake GitHubApi by swapping the manager's transport answers is overkill; selector tests run against real manager + transport. */
class RepositorySelectorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun repoJson(name: String, id: Long, push: Boolean = true) =
        """{"id":$id,"name":"$name","private":true,"default_branch":"main","html_url":"https://github.com/musty/$name","owner":{"login":"musty"},"permissions":{"push":$push}}"""

    private fun fixture(scopes: String = "repo", method: AuthMethod = AuthMethod.OAUTH_PRIVATE): Triple<AuthFixture, CloudBuildConfigStore, InMemoryProjectRepository> {
        val f = AuthFixture(method = method, scopes = scopes)
        f.transport.on("GET", "/user/repos", jsonResponse(200, "[${repoJson("builds", 1)},${repoJson("readonly", 2, push = false)}]"))
        f.transport.on("GET", "/branches?", jsonResponse(200, """[{"name":"main","commit":{"sha":"s1"}},{"name":"dev","commit":{"sha":"s2"}}]"""))
        f.transport.on("GET", "/commits/", jsonResponse(200, """{"sha":"abcdef123456","commit":{"message":"Hello","author":{"name":"Musty","date":"d"}}}"""))
        return Triple(f, CloudBuildConfigStore(InMemoryKeyValueStore()), InMemoryProjectRepository(listOf(testProject("p1", name = "My App"))))
    }

    @Test
    fun not_connected_lists_nothing_and_makes_no_repo_calls() {
        val f = AuthFixture(connected = false)
        val vm = RepositorySelectorViewModel("p1", f.manager, CloudBuildConfigStore(InMemoryKeyValueStore()), InMemoryProjectRepository())
        assertFalse(vm.state.value.connected)
        assertFalse(vm.state.value.loading)
        assertTrue(f.transport.requests.isEmpty())
    }

    @Test
    fun lists_repositories_and_filters_by_search() {
        val (f, configs, projects) = fixture()
        val vm = RepositorySelectorViewModel("p1", f.manager, configs, projects)
        assertEquals(listOf("musty/builds", "musty/readonly"), vm.state.value.repos.map { it.fullName })
        vm.onQueryChange("READ")
        assertEquals(listOf("musty/readonly"), vm.state.value.visibleRepos.map { it.fullName })
    }

    @Test
    fun api_errors_are_shown_not_thrown() {
        val f = AuthFixture()
        f.transport.on("GET", "/user/repos", jsonResponse(403, "{}", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "9999999999")))
        val vm = RepositorySelectorViewModel("p1", f.manager, CloudBuildConfigStore(InMemoryKeyValueStore()), InMemoryProjectRepository())
        assertTrue(vm.state.value.error!!.contains("rate limit"))
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun read_only_repositories_cannot_be_selected() {
        val (f, configs, projects) = fixture()
        val vm = RepositorySelectorViewModel("p1", f.manager, configs, projects)
        vm.openRepo(vm.state.value.repos.first { it.name == "readonly" })
        assertNull(vm.state.value.detail)
        assertTrue(vm.state.value.message!!.contains("read access"))
    }

    @Test
    fun choosing_a_repo_loads_branches_and_latest_commit_and_confirm_saves_the_config() {
        val (f, configs, projects) = fixture()
        val vm = RepositorySelectorViewModel("p1", f.manager, configs, projects)
        vm.openRepo(vm.state.value.repos.first())
        val detail = vm.state.value.detail!!
        assertEquals(listOf("main", "dev"), detail.branches.map { it.name })
        assertEquals("abcdef1", detail.latestCommit!!.shortSha)

        vm.selectBranch("dev")
        var done = false
        vm.confirm { done = true }
        assertTrue(done)
        val saved = configs.get("p1")!!
        assertEquals("musty/builds", saved.repoFullName)
        assertEquals("dev", saved.branch)
        assertEquals("almus-projects/my-app", saved.projectDir)
        assertEquals(CloudBuildConfig.DEFAULT_GRADLE_VERSION, saved.gradleVersion)
    }

    @Test
    fun browsing_without_a_project_only_remembers_the_default() {
        val (f, configs, projects) = fixture()
        val vm = RepositorySelectorViewModel(RepositorySelectorViewModel.NO_PROJECT, f.manager, configs, projects)
        vm.openRepo(vm.state.value.repos.first())
        vm.confirm { }
        assertNull(configs.get(RepositorySelectorViewModel.NO_PROJECT))
        assertEquals("musty/builds", configs.last()!!.repoFullName)
    }

    @Test
    fun invalid_branch_names_are_rejected_before_any_request() {
        val (f, configs, projects) = fixture()
        val vm = RepositorySelectorViewModel("p1", f.manager, configs, projects)
        vm.openRepo(vm.state.value.repos.first())
        val before = f.transport.requests.size
        vm.createBranch("bad branch name")
        assertNotNull(vm.state.value.detail!!.error)
        assertEquals(before, f.transport.requests.size)
    }

    @Test
    fun creating_a_branch_copies_the_default_branch_and_selects_it() {
        val (f, configs, projects) = fixture()
        f.transport.on("POST", "/git/refs", jsonResponse(201, "{}"))
        val vm = RepositorySelectorViewModel("p1", f.manager, configs, projects)
        vm.openRepo(vm.state.value.repos.first())
        vm.createBranch("almus-builds")
        val body = f.transport.requests.first { it.url.endsWith("/git/refs") }.body!!
        assertTrue(body.contains("refs/heads/almus-builds") && body.contains("s1"))
    }

    @Test
    fun repository_creation_is_allowed_only_when_the_granted_scopes_permit_it() {
        // GitHub App: never.
        val app = AuthFixture(method = AuthMethod.GITHUB_APP, scopes = "")
        app.transport.on("GET", "/user/installations?", jsonResponse(200, """{"installations":[]}"""))
        val appVm = RepositorySelectorViewModel("p1", app.manager, CloudBuildConfigStore(InMemoryKeyValueStore()), InMemoryProjectRepository())
        appVm.createRepository("x", "", isPrivate = false)
        assertTrue(appVm.state.value.message!!.contains("not allowed"))
        assertEquals(0, app.transport.count("POST", "/user/repos"))

        // public_repo: public yes, private no.
        val (f, configs, projects) = fixture(scopes = "public_repo", method = AuthMethod.OAUTH_PUBLIC)
        f.transport.on("POST", "/user/repos", jsonResponse(201, repoJson("fresh", 9)))
        val vm = RepositorySelectorViewModel("p1", f.manager, configs, projects)
        vm.createRepository("secret", "", isPrivate = true)
        assertEquals(0, f.transport.count("POST", "/user/repos"))
        vm.createRepository("fresh", "", isPrivate = false)
        assertEquals(1, f.transport.count("POST", "/user/repos"))
        assertEquals("musty/fresh", vm.state.value.detail!!.repo.fullName)
    }

    @Test
    fun invalid_repository_names_are_rejected_locally() {
        val (f, configs, projects) = fixture()
        val vm = RepositorySelectorViewModel("p1", f.manager, configs, projects)
        vm.createRepository("bad name!", "", false)
        assertEquals(0, f.transport.count("POST", "/user/repos"))
        assertNotNull(vm.state.value.message)
    }
}

class CloudBuildAndStatusViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = FakeClock()
    private val api = FakeGitHubApi()
    private val builds = InMemoryBuildStore()
    private val configs = CloudBuildConfigStore(InMemoryKeyValueStore())
    private val projects = InMemoryProjectRepository(listOf(testProject("p1", name = "My App")))
    private val orchestrator = CloudBuildOrchestrator(api, NoFiles, builds, now = clock::now, sleep = clock::sleep)
    private val tracker = BuildTracker(orchestrator, builds, CoroutineScope(Dispatchers.Unconfined), clock::now)

    private fun connectedAuth(): GitHubAuthManager = AuthFixture().manager

    private fun vm(auth: GitHubAuthManager = connectedAuth()) =
        CloudBuildViewModel("p1", projects, configs, auth, orchestrator, tracker) { "workflow text" }

    @Test
    fun without_a_repository_the_build_cannot_start() {
        val vm = vm()
        val s = vm.state.value
        assertTrue(s.connected)
        assertNull(s.config)
        assertFalse(s.canStart)
        vm.startBuild()
        assertFalse(vm.state.value.starting)
        assertTrue(api.calls.isEmpty())
    }

    @Test
    fun not_connected_blocks_the_build() {
        val vm = vm(AuthFixture(connected = false).manager)
        configs.save(CloudBuildConfig("p1", "musty", "builds", "main", "almus-projects/my-app"))
        vm.load()
        assertFalse(vm.state.value.connected)
        assertFalse(vm.state.value.canStart)
    }

    @Test
    fun missing_project_shows_an_error() {
        val vm = CloudBuildViewModel("nope", projects, configs, connectedAuth(), orchestrator, tracker) { "" }
        assertNull(vm.state.value.project)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun defaults_come_from_the_project_and_edits_are_validated_and_saved() {
        configs.save(CloudBuildConfig("p1", "musty", "builds", "main", "", "8.9"))
        val vm = vm()
        assertEquals("almus-projects/my-app", vm.state.value.dir)

        vm.onDirChange("../evil")
        assertNotNull(vm.state.value.dirError)
        assertFalse(vm.state.value.canStart)
        assertEquals("almus-projects/my-app", configs.get("p1")!!.projectDir.ifBlank { "almus-projects/my-app" })

        vm.onDirChange("apps/mine")
        vm.onGradleChange("8.10.2")
        assertNull(vm.state.value.dirError)
        assertEquals("apps/mine", configs.get("p1")!!.projectDir)
        assertEquals("8.10.2", configs.get("p1")!!.gradleVersion)

        vm.onGradleChange("latest")
        assertNotNull(vm.state.value.gradleError)
        assertEquals("invalid value not saved", "8.10.2", configs.get("p1")!!.gradleVersion)
    }

    @Test
    fun rejected_build_shows_reasons_and_creates_no_history() {
        configs.save(CloudBuildConfig("p1", "musty", "builds", "main", "almus-projects/my-app"))
        val vm = vm()
        assertTrue(vm.state.value.canStart)
        vm.startBuild() // NoFiles => the project has no files => validation rejects
        assertFalse(vm.state.value.starting)
        assertTrue(vm.state.value.rejected.isNotEmpty())
        assertNull(vm.startedBuildId.value)
        assertTrue(kotlinx.coroutines.runBlocking { builds.getBuildHistory() }.isEmpty())
    }

    @Test
    fun check_runs_read_only_and_reports_blockers() {
        configs.save(CloudBuildConfig("p1", "musty", "builds", "ghost", "almus-projects/my-app"))
        val vm = vm()
        vm.check()
        val result = vm.state.value.preflight!!
        assertFalse(result.canBuild)
        assertFalse(api.calls.contains("commitDirectory"))
        assertEquals("workflow text", vm.workflowText())
    }

    // ---- status screen ----

    private fun running(id: String = "b1") = BuildRecord(
        id, "My App", BuildStatus.RUNNING, 1L, null, "s", false, repoFullName = "musty/builds", branch = "main", runId = 5L,
    )

    @Test
    fun status_view_model_reports_not_found_and_found() {
        val missing = BuildStatusViewModel("zzz", builds, tracker)
        assertEquals(BuildStatusState.NotFound, missing.state.value)

        kotlinx.coroutines.runBlocking { builds.saveBuild(running().copy(status = BuildStatus.SUCCESS, finishedAtMillis = 2L)) }
        val vm = BuildStatusViewModel("b1", builds, tracker)
        val s = vm.state.value as BuildStatusState.Found
        assertEquals(BuildStatus.SUCCESS, s.record.status)
        assertFalse(s.polling)
    }

    @Test
    fun status_view_model_resumes_an_unfinished_build_and_picks_up_the_final_state() {
        kotlinx.coroutines.runBlocking { builds.saveBuild(running()) }
        api.runAnswers.addAll(listOf(com.almus.appcreatorpro.testutil.run(status = "completed", conclusion = "failure")))
        val vm = BuildStatusViewModel("b1", builds, tracker)
        val s = vm.state.value as BuildStatusState.Found
        assertEquals(BuildStatus.FAILED, s.record.status)
    }

    @Test
    fun history_view_model_is_live_for_real_stores_and_still_works_for_sample_data() {
        kotlinx.coroutines.runBlocking { builds.saveBuild(running()) }
        val live = BuildHistoryViewModel(builds)
        assertEquals(1, (live.state.value as UiState.Success).data.size)
        kotlinx.coroutines.runBlocking { builds.saveBuild(running("b2").copy(startedAtMillis = 9L)) }
        assertEquals(listOf("b2", "b1"), (live.state.value as UiState.Success).data.map { it.id })

        val sample = BuildHistoryViewModel(SampleBuildRepository(clock = { 10_000_000L }))
        assertEquals(4, (sample.state.value as UiState.Success).data.size)
    }
}
