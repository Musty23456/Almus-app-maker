package com.almus.appcreatorpro.monitor

import com.almus.appcreatorpro.data.repository.InMemoryBuildStore
import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.service.build.BuildService
import com.almus.appcreatorpro.service.monitor.BuildRefresher
import com.almus.appcreatorpro.service.monitor.MonitorSettingsStore
import com.almus.appcreatorpro.service.monitor.NotificationAccessStatus
import com.almus.appcreatorpro.service.monitor.NotificationController
import com.almus.appcreatorpro.service.monitor.SyncReport
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.dashboard.ArtifactAvailability
import com.almus.appcreatorpro.ui.dashboard.BuildDashboardViewModel
import com.almus.appcreatorpro.ui.dashboard.BuildGroup
import com.almus.appcreatorpro.ui.dashboard.DashboardBuilder
import com.almus.appcreatorpro.ui.dashboard.DashboardFilter
import com.almus.appcreatorpro.ui.dashboard.DashboardState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private const val NOW = 50_000_000L

private fun apk(expires: Long? = null, status: ArtifactStatus = ArtifactStatus.AVAILABLE, name: String = "almus-apk-x") =
    BuildArtifactInfo(1, name, 3L * 1024 * 1024, expires, status)

private fun sampleBuilds(): List<BuildRecord> = listOf(
    record("run", BuildStatus.RUNNING, NOW - 125_000, configuration = BuildConfiguration(BuildVariant.RELEASE_AAB)),
    record("queued", BuildStatus.QUEUED, NOW - 20_000),
    record("ok", BuildStatus.SUCCESS, NOW - 900_000, finishedAt = NOW - 800_000).copy(durationSeconds = 100, artifacts = listOf(apk())),
    record("bad", BuildStatus.FAILED, NOW - 1_000_000, finishedAt = NOW - 990_000, detail = "The build failed at step \"Build\".").copy(durationSeconds = 10),
    record("off", BuildStatus.CANCELLED, NOW - 2_000_000, finishedAt = NOW - 1_990_000).copy(durationSeconds = 10),
)

class DashboardBuilderTest {
    private fun build(filter: DashboardFilter = DashboardFilter.ALL, followed: Set<String> = emptySet(), saved: Set<String> = emptySet()) =
        DashboardBuilder.build(sampleBuilds(), NOW, followed, saved, filter)

    @Test
    fun the_summary_counts_active_completed_failed_and_cancelled_builds() {
        val s = build().summary
        assertEquals(2, s.active); assertEquals(1, s.completed); assertEquals(1, s.failed); assertEquals(1, s.cancelled)
        assertEquals(5, s.total)
    }

    @Test
    fun active_builds_are_listed_apart_from_the_history_newest_first() {
        val d = build()
        assertEquals(listOf("queued", "run"), d.active.map { it.id })
        assertEquals(listOf("ok", "bad", "off"), d.history.map { it.id })
    }

    @Test
    fun filters_show_only_their_group() {
        assertEquals(listOf("queued", "run"), build(DashboardFilter.ACTIVE).active.map { it.id })
        assertTrue(build(DashboardFilter.ACTIVE).history.isEmpty())
        assertEquals(listOf("ok"), build(DashboardFilter.COMPLETED).history.map { it.id })
        assertEquals(listOf("bad"), build(DashboardFilter.FAILED).history.map { it.id })
        assertEquals(listOf("off"), build(DashboardFilter.CANCELLED).history.map { it.id })
        // The summary does not change with the filter.
        assertEquals(5, build(DashboardFilter.FAILED).summary.total)
    }

    @Test
    fun an_active_build_shows_the_time_it_has_been_running_and_a_finished_one_its_final_duration() {
        val d = build()
        assertEquals(125, d.active.first { it.id == "run" }.durationSeconds)
        assertEquals(100, d.history.first { it.id == "ok" }.durationSeconds)
    }

    @Test
    fun build_type_status_and_live_marker_are_shown() {
        val run = build(followed = setOf("run")).active.first { it.id == "run" }
        assertEquals("Release AAB", run.buildType)
        assertEquals("Running", run.statusText)
        assertTrue(run.isLive)
        val queued = build().active.first { it.id == "queued" }
        assertEquals("Debug APK", queued.buildType)
        assertFalse(queued.isLive)
    }

    @Test
    fun cancel_is_offered_for_active_builds_only_and_not_twice() {
        val items = build().active + build().history
        assertTrue(items.filter { it.group == BuildGroup.ACTIVE }.all { it.canCancel })
        assertTrue(items.filter { it.group != BuildGroup.ACTIVE }.none { it.canCancel })
        val cancelling = DashboardBuilder.toItem(sampleBuilds()[0].copy(cancelRequested = true), NOW, emptySet(), emptySet())
        assertFalse(cancelling.canCancel)
        assertEquals("Cancelling", cancelling.statusText)
    }

    @Test
    fun a_build_whose_checking_stopped_asks_for_attention() {
        val item = DashboardBuilder.toItem(sampleBuilds()[0].copy(pollingStopped = true), NOW, emptySet(), emptySet())
        assertTrue(item.needsAttention)
    }

    @Test
    fun artifact_availability_is_reported_for_every_situation() {
        fun avail(r: BuildRecord, saved: Boolean = false) =
            DashboardBuilder.toItem(r, NOW, emptySet(), if (saved) setOf(r.id) else emptySet()).artifact.availability
        val base = sampleBuilds()
        assertEquals(ArtifactAvailability.PENDING, avail(base[0]))
        assertEquals(ArtifactAvailability.AVAILABLE, avail(base[2]))
        assertEquals(ArtifactAvailability.ON_PHONE, avail(base[2], saved = true))
        assertEquals(ArtifactAvailability.EXPIRED, avail(base[2].copy(artifacts = listOf(apk(status = ArtifactStatus.EXPIRED)))))
        // The expiry date passing counts even before GitHub is asked again.
        assertEquals(ArtifactAvailability.EXPIRED, avail(base[2].copy(artifacts = listOf(apk(expires = NOW - 1)))))
        assertEquals(ArtifactAvailability.DELETED, avail(base[2].copy(artifacts = listOf(apk(status = ArtifactStatus.DELETED)))))
        assertEquals(ArtifactAvailability.NONE, avail(base[3]))
        assertTrue(DashboardBuilder.toItem(base[2], NOW, emptySet(), emptySet()).artifact.text.startsWith("APK ready"))
        val aab = base[2].copy(artifacts = listOf(apk(name = "almus-aab-x")))
        assertTrue(DashboardBuilder.toItem(aab, NOW, emptySet(), emptySet()).artifact.text.startsWith("AAB ready"))
    }

    @Test
    fun logs_and_hand_over_files_do_not_count_as_artifacts() {
        val r = sampleBuilds()[2].copy(artifacts = listOf(apk(name = "almus-build-log-x")))
        assertEquals(ArtifactAvailability.NONE, DashboardBuilder.toItem(r, NOW, emptySet(), emptySet()).artifact.availability)
    }

    @Test
    fun sample_builds_are_not_shown() {
        val d = DashboardBuilder.build(listOf(record("s").copy(isSample = true)), NOW, emptySet(), emptySet(), DashboardFilter.ALL)
        assertTrue(d.isEmpty)
        assertEquals(0, d.summary.total)
    }
}

class BuildDashboardViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private class FakeBuildService : BuildService {
        override val displayName = "fake"
        override val isAvailable = true
        val cancelled = mutableListOf<String>()
        var result: Result<String> = Result.success("Cancel sent to GitHub.")
        override suspend fun requestBuild(project: Project): Result<BuildRecord> = Result.failure(IllegalStateException())
        override suspend fun cancelBuild(record: BuildRecord): Result<String> { cancelled += record.id; return result }
    }

    private val store = InMemoryBuildStore(sampleBuilds())
    private val follower = FakeFollower()
    private val service = FakeBuildService()
    private var access = NotificationAccessStatus.ENABLED
    private val controller = NotificationController(MonitorSettingsStore(InMemoryKeyValueStore())) { access }
    private var refreshReport = SyncReport()
    private var refreshCalls = 0

    private fun vm(refresher: BuildRefresher? = BuildRefresher { refreshCalls++; refreshReport }) = BuildDashboardViewModel(
        builds = store, follower = follower, buildService = service, refresher = refresher,
        notificationController = controller, now = { NOW }, tickMillis = 0,
    )

    private fun BuildDashboardViewModel.data(): DashboardState = (state.value as UiState.Success).data

    @Test
    fun the_dashboard_shows_the_stored_builds_straight_away() {
        val d = vm().data()
        assertEquals(5, d.summary.total)
        assertEquals(2, d.active.size)
    }

    @Test
    fun the_dashboard_follows_changes_made_by_the_tracker_or_the_background_sync() {
        val viewModel = vm()
        runBlocking { store.saveBuild(sampleBuilds()[0].copy(status = BuildStatus.SUCCESS, finishedAtMillis = NOW, durationSeconds = 130)) }
        assertEquals(1, viewModel.data().active.size)
        assertEquals(2, viewModel.data().summary.completed)
    }

    @Test
    fun the_live_marker_follows_the_trackers_active_builds() {
        val viewModel = vm()
        follower.setFollowed(setOf("run"))
        assertTrue(viewModel.data().active.first { it.id == "run" }.isLive)
    }

    @Test
    fun the_filter_changes_what_is_listed() {
        val viewModel = vm()
        viewModel.setFilter(DashboardFilter.FAILED)
        assertEquals(listOf("bad"), viewModel.data().history.map { it.id })
        assertTrue(viewModel.data().active.isEmpty())
    }

    @Test
    fun refresh_runs_the_check_and_tells_the_user_the_result() {
        val viewModel = vm()
        refreshReport = SyncReport(finished = 2)
        viewModel.refresh()
        assertEquals(1, refreshCalls)
        assertEquals("2 builds finished.", viewModel.message.value)
        assertFalse(viewModel.refreshing.value)
    }

    @Test
    fun refresh_failures_are_reported_not_thrown() {
        val viewModel = vm(BuildRefresher { throw IllegalStateException("boom") })
        viewModel.refresh()
        assertEquals("The status could not be refreshed. Try again.", viewModel.message.value)
        assertFalse(viewModel.refreshing.value)
    }

    @Test
    fun refresh_without_a_refresher_says_so() {
        val viewModel = vm(refresher = null)
        viewModel.refresh()
        assertEquals("Refreshing is not available.", viewModel.message.value)
    }

    @Test
    fun cancel_asks_github_through_the_build_service_and_shows_its_answer() {
        val viewModel = vm()
        viewModel.cancel("run")
        assertEquals(listOf("run"), service.cancelled)
        assertEquals("Cancel sent to GitHub.", viewModel.message.value)
        assertTrue(viewModel.cancelling.value.isEmpty())
    }

    @Test
    fun a_failed_cancel_shows_the_reason() {
        service.result = Result.failure(IllegalStateException("GitHub could not cancel the build."))
        val viewModel = vm()
        viewModel.cancel("run")
        assertEquals("GitHub could not cancel the build.", viewModel.message.value)
    }

    @Test
    fun a_finished_build_is_not_sent_for_cancelling() {
        val viewModel = vm()
        viewModel.cancel("ok")
        assertTrue(service.cancelled.isEmpty())
        assertEquals("This build has already finished.", viewModel.message.value)
    }

    @Test
    fun notification_state_and_permission_flow_are_exposed() {
        access = NotificationAccessStatus.PERMISSION_NEEDED
        val viewModel = vm()
        viewModel.refreshNotificationState()
        assertTrue(viewModel.notifications.value!!.needsPermission)
        assertTrue(viewModel.enableNotifications())
        viewModel.onNotificationPermissionResult(false)
        assertFalse(viewModel.notifications.value!!.enabled)
        assertTrue(viewModel.message.value!!.contains("Notifications stay off"))
    }

    @Test
    fun without_a_controller_notification_state_is_null() {
        val viewModel = BuildDashboardViewModel(store, follower, service, tickMillis = 0, now = { NOW })
        assertNull(viewModel.notifications.value)
        assertFalse(viewModel.enableNotifications())
    }
}
