package com.almus.appcreatorpro.ui

import com.almus.appcreatorpro.data.repository.CloudBuildConfigStore
import com.almus.appcreatorpro.data.repository.InMemoryBuildStore
import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.github.WorkflowArtifact
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.github.WorkflowStep
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.artifacts.ApkDownloadManager
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloader
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.service.build.BuildDetailsService
import com.almus.appcreatorpro.service.build.BuildTracker
import com.almus.appcreatorpro.service.build.CloudBuildOrchestrator
import com.almus.appcreatorpro.service.build.GitHubActionsBuildService
import com.almus.appcreatorpro.service.github.GitHubAuthManager
import com.almus.appcreatorpro.service.github.InMemorySecretStore
import com.almus.appcreatorpro.service.github.TokenProvider
import com.almus.appcreatorpro.testutil.FakeClock
import com.almus.appcreatorpro.testutil.FakeGitHubApi
import com.almus.appcreatorpro.testutil.FakeStreamTransport
import com.almus.appcreatorpro.testutil.FakeTransport
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.testutil.Reply
import com.almus.appcreatorpro.testutil.artifactZip
import com.almus.appcreatorpro.testutil.ghError
import com.almus.appcreatorpro.testutil.jsonResponse
import com.almus.appcreatorpro.testutil.run
import com.almus.appcreatorpro.ui.builds.ApkAvailability
import com.almus.appcreatorpro.ui.builds.BuildHistoryViewModel
import com.almus.appcreatorpro.ui.builds.BuildStatusViewModel
import com.almus.appcreatorpro.ui.builds.DetailsUi
import com.almus.appcreatorpro.ui.builds.ExportUi
import com.almus.appcreatorpro.ui.builds.LogUi
import com.almus.appcreatorpro.ui.builds.RetryUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class BuildResultViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val signedUrl = "https://storage.example.net/blob?sig=x"
    private val clock = FakeClock(nowMillisStart())
    private val api = FakeGitHubApi()
    private val builds = InMemoryBuildStore()
    private val stream = FakeStreamTransport()
    private val authTransport = FakeTransport()
    private val meta = InMemoryKeyValueStore()
    private val secrets = InMemorySecretStore()

    private fun nowMillisStart() = 1_000_000L

    private val artifact = BuildArtifactInfo(42, "almus-apk-abc123def456", 4_000_000, expiresAtMillis = 50_000_000L)

    private fun record(
        status: BuildStatus = BuildStatus.SUCCESS,
        artifacts: List<BuildArtifactInfo> = listOf(artifact),
        id: String = "b1",
    ) = BuildRecord(
        id = id, projectName = "My App", status = status, startedAtMillis = 1L, durationSeconds = 60, summary = "musty/builds (main)",
        isSample = false, projectId = "p1", repoFullName = "musty/builds", branch = "main", projectDir = "almus-projects/my-app", runId = 77,
        runUrl = "https://github.com/musty/builds/actions/runs/77", artifacts = artifacts, artifactNames = artifacts.map { it.name },
        finishedAtMillis = if (status.isTerminal) 2L else null,
    )

    private lateinit var store: ApkStore
    private lateinit var downloads: ApkDownloadManager
    private lateinit var auth: GitHubAuthManager
    private lateinit var tracker: BuildTracker
    private lateinit var service: GitHubActionsBuildService
    private lateinit var projects: FileProjectStore

    private fun setUp(connected: Boolean = true) {
        store = ApkStore(tmp.newFolder("apks"))
        downloads = ApkDownloadManager(
            ArtifactDownloader(stream, TokenProvider { "ghu_abcdefghijklmnopqrstuvwxyz123456" }), store, builds,
            CoroutineScope(Dispatchers.Unconfined), now = clock::now,
        )
        auth = GitHubAuthManager(meta, secrets, authTransport, now = clock::now)
        if (connected) {
            secrets.put("github_access_token", "ghu_abcdefghijklmnopqrstuvwxyz123456")
            meta.putString("github_username", "musty")
            authTransport.on("GET", "/user", jsonResponse(200, """{"login":"musty"}"""))
            kotlinx.coroutines.runBlocking { auth.ensureVerified() }
        }
        projects = FileProjectStore(rootDir = tmp.newFolder("projects"), ioDispatcher = Dispatchers.Unconfined)
        val orchestrator = CloudBuildOrchestrator(api, projects, builds, now = clock::now, sleep = clock::sleep)
        tracker = BuildTracker(orchestrator, builds, CoroutineScope(Dispatchers.Unconfined), clock::now)
        service = GitHubActionsBuildService(auth, CloudBuildConfigStore(InMemoryKeyValueStore()), tracker, projects)
    }

    private fun statusVm(id: String = "b1") = BuildStatusViewModel(
        buildId = id, builds = builds, tracker = tracker, detailsService = BuildDetailsService(api, builds),
        downloads = downloads, apkStore = store, buildService = service, auth = auth, now = clock::now,
    )

    private fun saveRecord(r: BuildRecord) = kotlinx.coroutines.runBlocking { builds.saveBuild(r) }

    private val goodJobs = listOf(WorkflowJob(5, "Build debug APK", "success", listOf(WorkflowStep("Build", "success"))))

    // ------------------------------------------------------------------ status screen

    @Test
    fun the_status_screen_loads_steps_and_artifacts_for_a_finished_build() {
        setUp()
        saveRecord(record())
        api.jobs = goodJobs
        api.artifacts = listOf(WorkflowArtifact(42, artifact.name, 4_000_000, false, 50_000_000L))
        val vm = statusVm()
        val d = (vm.details.value as DetailsUi.Loaded).details
        assertEquals(1, d.jobs.size)
        assertEquals(artifact.name, d.artifacts.single().name)
        assertNull("no log is fetched for a successful build", d.log)
        assertEquals(LogUi.Hidden, vm.log.value)
    }

    @Test
    fun a_failed_build_shows_its_log_and_errors_automatically() {
        setUp()
        saveRecord(record(BuildStatus.FAILED, artifacts = emptyList()))
        api.jobs = listOf(WorkflowJob(5, "Build", "failure", listOf(WorkflowStep("Build debug APK", "failure"))))
        api.logTail = "e: file:///a/b/Main.kt:3:4 Unresolved reference: x"
        val vm = statusVm()
        val shown = vm.log.value as LogUi.Shown
        assertTrue(shown.view.errors.single().endsWith("Main.kt:3:4  Unresolved reference: x"))
    }

    @Test
    fun the_log_can_be_requested_for_a_successful_build() {
        setUp()
        saveRecord(record())
        api.jobs = goodJobs
        api.logTail = "BUILD SUCCESSFUL in 1m"
        val vm = statusVm()
        vm.loadLog()
        assertEquals("BUILD SUCCESSFUL in 1m", (vm.log.value as LogUi.Shown).view.text)
    }

    @Test
    fun details_errors_are_reported_and_can_be_retried() {
        setUp()
        saveRecord(record())
        api.artifactsError = ghError(com.almus.appcreatorpro.domain.github.GitHubException.Kind.NETWORK, "offline")
        val failing = object : com.almus.appcreatorpro.domain.github.GitHubApi by api {
            override suspend fun listJobs(owner: String, name: String, runId: Long): List<WorkflowJob> =
                throw ghError(com.almus.appcreatorpro.domain.github.GitHubException.Kind.NETWORK, "offline")
        }
        val vm = BuildStatusViewModel("b1", builds, tracker, BuildDetailsService(failing, builds), downloads, store, service, auth)
        val e = vm.details.value as DetailsUi.Error
        assertTrue(e.retryable)
    }

    @Test
    fun an_expired_sign_in_is_visible_to_the_screen() {
        setUp()
        saveRecord(record(BuildStatus.RUNNING, artifacts = emptyList()))
        api.runAnswers.addAll(listOf(ghError(com.almus.appcreatorpro.domain.github.GitHubException.Kind.TOKEN_REJECTED, "expired")))
        val vm = statusVm()
        assertFalse(vm.needsReconnect.value)
        auth.notifyTokenRejected()
        assertTrue(vm.needsReconnect.value)
    }

    // ------------------------------------------------------------------ downloads from the status screen

    @Test
    fun downloading_saves_the_apk_and_lists_it() {
        setUp()
        saveRecord(record())
        api.jobs = goodJobs
        stream.reset(Reply.redirect(signedUrl), Reply.ok(artifactZip()))
        val vm = statusVm()
        assertNull(vm.download(artifact))
        assertTrue(vm.downloadStates.value[DownloadKey("b1", 42)] is DownloadState.Finished)
        assertEquals(listOf("app-debug.apk"), vm.savedApks.value.map { it.fileName })
    }

    @Test
    fun an_expired_artifact_gives_a_message_and_no_request() {
        setUp()
        val old = artifact.copy(status = ArtifactStatus.EXPIRED)
        saveRecord(record(artifacts = listOf(old)))
        val vm = statusVm()
        val msg = vm.download(old)
        assertTrue(msg!!, msg.contains("expired"))
        assertTrue(stream.requests.isEmpty())
    }

    @Test
    fun saved_apks_can_be_deleted_and_exported_to_a_chosen_destination() {
        setUp()
        saveRecord(record())
        api.jobs = goodJobs
        stream.reset(Reply.redirect(signedUrl), Reply.ok(artifactZip()))
        val vm = statusVm()
        vm.download(artifact)
        val apk = vm.savedApks.value.single()
        val out = ByteArrayOutputStream()
        vm.exportApk(apk) { out }
        assertEquals(ExportUi.Done("app-debug.apk"), vm.export.value)
        assertArrayEquals(apk.file.readBytes(), out.toByteArray())
        vm.exportApk(apk) { null }
        assertTrue(vm.export.value is ExportUi.Failed)
        vm.exportApk(apk) { object : OutputStream() { override fun write(b: Int) { throw IOException("disk full") } } }
        assertTrue((vm.export.value as ExportUi.Failed).message.contains("disk full"))
        vm.deleteApk(apk)
        assertTrue(vm.savedApks.value.isEmpty())
    }

    // ------------------------------------------------------------------ retry

    @Test
    fun retry_starts_a_new_build_and_reports_its_id() {
        setUp()
        val project = kotlinx.coroutines.runBlocking { projects.createProject("My App", "com.example.myapp", "") }
        api.installWorkflow("main")
        api.runAnswers += run(status = "queued")
        api.findAnswers += run(status = "queued")
        saveRecord(record(BuildStatus.FAILED, artifacts = emptyList()).copy(projectId = project.id))
        api.jobs = goodJobs
        val vm = statusVm()
        vm.retryBuild()
        val started = vm.retry.value
        assertTrue(started.toString(), started is RetryUi.Started)
        assertTrue((started as RetryUi.Started).newBuildId != "b1")
        assertEquals(BuildStatus.FAILED, kotlinx.coroutines.runBlocking { builds.getBuild("b1")!!.status })
    }

    @Test
    fun retry_failures_are_explained() {
        setUp()
        saveRecord(record(BuildStatus.FAILED, artifacts = emptyList()).copy(projectId = "deleted-project"))
        val vm = statusVm()
        vm.retryBuild()
        val failed = vm.retry.value as RetryUi.Failed
        assertTrue(failed.message.contains("no longer exists"))
        vm.clearRetry()
        assertEquals(RetryUi.Idle, vm.retry.value)
    }

    @Test
    fun old_style_construction_still_works() {
        setUp()
        saveRecord(record())
        val vm = BuildStatusViewModel("b1", builds, tracker)
        assertNotNull(vm.state.value)
        assertEquals(DetailsUi.Idle, vm.details.value)
        assertEquals("Downloads are not available.", vm.download(artifact))
    }

    // ------------------------------------------------------------------ history

    private fun historyVm() = BuildHistoryViewModel(builds, downloads, store, clock::now)

    @Test
    fun history_tells_successful_failed_and_expired_builds_apart() {
        setUp()
        val ok = record(id = "ok")
        val gone = record(id = "gone", artifacts = listOf(artifact.copy(status = ArtifactStatus.EXPIRED)))
        val deleted = record(id = "deleted", artifacts = listOf(artifact.copy(status = ArtifactStatus.DELETED)))
        val failed = record(BuildStatus.FAILED, artifacts = emptyList(), id = "failed")
        listOf(ok, gone, deleted, failed).forEach(::saveRecord)
        val vm = historyVm()
        assertEquals(ApkAvailability.DOWNLOADABLE, vm.availability(ok))
        assertEquals(ApkAvailability.EXPIRED, vm.availability(gone))
        assertEquals(ApkAvailability.DELETED, vm.availability(deleted))
        assertEquals(ApkAvailability.NONE, vm.availability(failed))
    }

    @Test
    fun history_downloads_an_available_apk_and_then_shows_it_as_saved() {
        setUp()
        val ok = record(id = "ok")
        saveRecord(ok)
        stream.reset(Reply.redirect(signedUrl), Reply.ok(artifactZip()))
        val vm = historyVm()
        vm.download(ok)
        assertTrue("ok" in vm.savedBuildIds.value)
        assertEquals(ApkAvailability.ON_PHONE, vm.availability(ok))
    }

    @Test
    fun history_explains_why_an_expired_or_missing_apk_cannot_be_downloaded() {
        setUp()
        val gone = record(id = "gone", artifacts = listOf(artifact.copy(status = ArtifactStatus.EXPIRED)))
        val none = record(BuildStatus.FAILED, artifacts = emptyList(), id = "none")
        saveRecord(gone); saveRecord(none)
        val vm = historyVm()
        vm.download(gone)
        assertTrue(vm.message.value!!.contains("expired"))
        vm.clearMessage()
        assertNull(vm.message.value)
        vm.download(none)
        assertTrue(vm.message.value!!.contains("no APK"))
        assertTrue(stream.requests.isEmpty())
    }

    @Test
    fun an_artifact_whose_expiry_date_passed_is_treated_as_expired_without_asking_github() {
        setUp()
        val r = record(id = "late")
        saveRecord(r)
        clock.nowMillis = 60_000_000L
        val vm = historyVm()
        assertEquals(ApkAvailability.EXPIRED, vm.availability(r))
        vm.download(r)
        assertTrue(stream.requests.isEmpty())
    }

    @Test
    fun history_download_progress_is_exposed_and_cancel_works() {
        setUp()
        val ok = record(id = "ok")
        saveRecord(ok)
        val gate = com.almus.appcreatorpro.testutil.Gate()
        stream.reset(Reply.redirect(signedUrl), Reply(200, emptyMap(), { java.io.ByteArrayInputStream(artifactZip()) }, before = { gate.await() }))
        val vm = historyVm()
        vm.download(ok)
        assertTrue(vm.downloadStates.value[DownloadKey("ok", 42)] is DownloadState.Active)
        vm.cancelDownload(ok)
        assertEquals(DownloadState.Cancelled, vm.downloadStates.value[DownloadKey("ok", 42)])
    }
}
