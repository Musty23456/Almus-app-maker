package com.almus.appcreatorpro.monitor

import com.almus.appcreatorpro.data.repository.FileBuildStore
import com.almus.appcreatorpro.data.repository.InMemoryBuildStore
import com.almus.appcreatorpro.domain.github.GitHubException.Kind
import com.almus.appcreatorpro.domain.github.WorkflowArtifact
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.build.SyncOutcome
import com.almus.appcreatorpro.service.monitor.SyncMode
import com.almus.appcreatorpro.testutil.ghError
import com.almus.appcreatorpro.testutil.run
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** One status check of one build (CloudBuildOrchestrator.syncOnce). */
class SyncOnceTest {
    private val rig = MonitorRig()

    private fun save(r: com.almus.appcreatorpro.domain.model.BuildRecord) = runBlocking { rig.store.saveBuild(r) }
    private fun sync(id: String, maxAge: Long = Long.MAX_VALUE) = runBlocking { rig.orchestrator.syncOnce(id, maxAge) }
    private fun get(id: String) = runBlocking { rig.store.getBuild(id)!! }

    @Test
    fun a_running_run_updates_the_record_and_is_not_rewritten_when_nothing_changed() {
        save(record("b1", BuildStatus.QUEUED, startedAt = rig.clock.now()))
        rig.api.runAnswers += run(100, "in_progress")
        assertTrue(sync("b1") is SyncOutcome.Progressed)
        assertEquals(BuildStatus.RUNNING, get("b1").status)
        assertTrue(sync("b1") is SyncOutcome.Unchanged)
    }

    @Test
    fun a_successful_run_is_finished_with_artifacts_and_duration() {
        val t = rig.clock.now()
        save(record("b1", BuildStatus.RUNNING, startedAt = t - 90_000))
        rig.api.runAnswers += run(100, "completed", "success")
        rig.api.artifacts = listOf(WorkflowArtifact(5, "almus-apk-b1", 2048, false))
        val out = sync("b1") as SyncOutcome.Finished
        assertEquals(BuildStatus.SUCCESS, out.record.status)
        assertEquals(90, out.record.durationSeconds)
        assertEquals(listOf("almus-apk-b1"), out.record.artifactNames)
        assertNotNull(out.record.finishedAtMillis)
    }

    @Test
    fun failed_and_cancelled_runs_are_final() {
        save(record("f", BuildStatus.RUNNING, startedAt = rig.clock.now(), runId = 1))
        save(record("c", BuildStatus.RUNNING, startedAt = rig.clock.now(), runId = 2))
        rig.api.runAnswersById[1] = answers(run(1, "completed", "failure"))
        rig.api.runAnswersById[2] = answers(run(2, "completed", "cancelled"))
        assertEquals(BuildStatus.FAILED, (sync("f") as SyncOutcome.Finished).record.status)
        assertEquals(BuildStatus.CANCELLED, (sync("c") as SyncOutcome.Finished).record.status)
    }

    @Test
    fun a_finished_build_is_never_checked_again() {
        save(record("b1", BuildStatus.SUCCESS, startedAt = rig.clock.now()))
        assertEquals(SyncOutcome.Skipped, sync("b1"))
        assertTrue(rig.api.getRunRequests.isEmpty())
    }

    @Test
    fun a_missing_build_and_a_sample_build_are_skipped() {
        assertEquals(SyncOutcome.Skipped, sync("nope"))
        save(record("s", BuildStatus.RUNNING).copy(isSample = true))
        assertEquals(SyncOutcome.Skipped, sync("s"))
    }

    @Test
    fun a_lost_connection_changes_nothing_so_the_check_can_simply_be_repeated() {
        save(record("b1", BuildStatus.RUNNING, startedAt = rig.clock.now()))
        rig.api.runAnswers += ghError(Kind.NETWORK, "offline")
        assertTrue(sync("b1") is SyncOutcome.TransientError)
        val r = get("b1")
        assertFalse(r.pollingStopped)
        assertEquals(BuildStatus.RUNNING, r.status)
    }

    @Test
    fun server_trouble_and_timeouts_are_transient_too() {
        save(record("b1", BuildStatus.RUNNING, startedAt = rig.clock.now()))
        for (k in listOf(Kind.SERVER, Kind.TIMEOUT)) {
            rig.api.runAnswers.clear(); rig.api.runAnswers += ghError(k)
            assertTrue(sync("b1") is SyncOutcome.TransientError)
        }
    }

    @Test
    fun a_rate_limit_reports_when_it_resets_and_changes_nothing() {
        save(record("b1", BuildStatus.RUNNING, startedAt = rig.clock.now()))
        rig.api.runAnswers += ghError(Kind.RATE_LIMITED, "slow down", retryAt = rig.clock.now() + 120_000)
        val out = sync("b1") as SyncOutcome.RateLimited
        assertEquals(rig.clock.now() + 120_000, out.retryAtMillis)
        assertFalse(get("b1").pollingStopped)
    }

    @Test
    fun a_lost_sign_in_stops_checking_and_says_how_to_continue() {
        save(record("b1", BuildStatus.RUNNING, startedAt = rig.clock.now()))
        rig.api.runAnswers += ghError(Kind.TOKEN_REJECTED)
        assertTrue(sync("b1") is SyncOutcome.AuthProblem)
        val r = get("b1")
        assertTrue(r.pollingStopped)
        assertTrue(r.detail!!.contains("Reconnect GitHub"))
    }

    @Test
    fun a_run_github_no_longer_knows_stops_checking() {
        save(record("b1", BuildStatus.RUNNING, startedAt = rig.clock.now()))
        rig.api.runAnswers += ghError(Kind.NOT_FOUND, "Run not found")
        assertTrue(sync("b1") is SyncOutcome.Stopped)
        assertTrue(get("b1").pollingStopped)
    }

    @Test
    fun a_run_that_does_not_exist_yet_is_waited_for_then_given_up_on() {
        val t = rig.clock.now()
        save(record("b1", BuildStatus.QUEUED, startedAt = t - 10_000, runId = null))
        assertTrue(sync("b1") is SyncOutcome.Unchanged)
        rig.clock.nowMillis = t + 200_000
        assertTrue(sync("b1") is SyncOutcome.Stopped)
        assertTrue(get("b1").pollingStopped)
    }

    @Test
    fun a_run_found_late_is_recorded_and_a_cancel_asked_before_is_sent_once() {
        save(record("b1", BuildStatus.PREPARING, startedAt = rig.clock.now(), runId = null).copy(cancelRequested = true))
        rig.api.findAnswers += run(100, "queued")
        rig.api.runAnswers += run(100, "queued")
        sync("b1")
        sync("b1")
        assertEquals(100L, get("b1").runId)
        assertEquals(1, rig.api.calls.count { it == "cancelRun" })
    }

    @Test
    fun a_build_older_than_the_limit_is_marked_stopped_without_asking_github() {
        save(record("b1", BuildStatus.RUNNING, startedAt = rig.clock.now() - 9 * 3_600_000L))
        assertTrue(sync("b1", maxAge = 8 * 3_600_000L) is SyncOutcome.Stopped)
        assertTrue(rig.api.getRunRequests.isEmpty())
        assertTrue(get("b1").pollingStopped)
    }

    @Test
    fun a_build_whose_checking_had_stopped_continues_when_github_still_runs_it() {
        save(record("b1", BuildStatus.RUNNING, startedAt = rig.clock.now(), stopped = true, detail = "Lost the connection"))
        rig.api.runAnswers += run(100, "in_progress")
        assertTrue(sync("b1") is SyncOutcome.Progressed)
        assertFalse(get("b1").pollingStopped)
    }
}

class BuildSyncEngineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun several_builds_at_once_are_each_checked_once_and_finish_independently() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        runBlocking {
            rig.store.saveBuild(record("a", BuildStatus.QUEUED, t - 3000, runId = 1, project = "Alpha"))
            rig.store.saveBuild(record("b", BuildStatus.QUEUED, t - 2000, runId = 2, project = "Beta"))
            rig.store.saveBuild(record("c", BuildStatus.QUEUED, t - 1000, runId = 3, project = "Gamma"))
        }
        rig.api.runAnswersById[1] = answers(run(1, "completed", "success"))
        rig.api.runAnswersById[2] = answers(run(2, "in_progress"))
        rig.api.runAnswersById[3] = answers(run(3, "completed", "failure"))
        val report = runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(3, report.checked)
        assertEquals(2, report.finished)
        assertEquals(1, report.updated)
        assertEquals(listOf(1L, 2L, 3L), rig.api.getRunRequests)
        val byId = runBlocking { rig.store.getBuildHistory() }.associateBy { it.id }
        assertEquals(BuildStatus.SUCCESS, byId.getValue("a").status)
        assertEquals(BuildStatus.RUNNING, byId.getValue("b").status)
        assertEquals(BuildStatus.FAILED, byId.getValue("c").status)
        // Every build has its own notification with its own project name.
        assertEquals(listOf("success"), rig.sink.kinds("a"))
        assertEquals(listOf("in_progress"), rig.sink.kinds("b"))
        assertEquals(listOf("failed"), rig.sink.kinds("c"))
        assertTrue(rig.sink.posted.first { it.buildId == "b" }.title.startsWith("Beta"))
        assertEquals(1, report.snapshot.watchable)
    }

    @Test
    fun builds_the_app_is_following_live_are_not_checked_again_in_the_background() {
        val rig = MonitorRig()
        runBlocking {
            rig.store.saveBuild(record("live", BuildStatus.RUNNING, rig.clock.now(), runId = 1))
            rig.store.saveBuild(record("idle", BuildStatus.RUNNING, rig.clock.now(), runId = 2))
        }
        rig.follower.setFollowed(setOf("live"))
        rig.api.runAnswers += run(2, "in_progress")
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(listOf(2L), rig.api.getRunRequests)
    }

    @Test
    fun the_same_build_is_not_asked_twice_within_the_minimum_gap_and_is_asked_again_after_it() {
        val rig = MonitorRig()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now(), runId = 1)) }
        rig.api.runAnswers += run(1, "in_progress")
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(1, rig.api.getRunRequests.size)
        rig.clock.nowMillis += 31_000
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(2, rig.api.getRunRequests.size)
    }

    @Test
    fun a_manual_refresh_ignores_the_gap_and_also_looks_at_stopped_builds() {
        val rig = MonitorRig()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now(), runId = 1, stopped = true)) }
        rig.api.runAnswers += run(1, "in_progress")
        runBlocking { rig.engine.syncAll(SyncMode.MANUAL) }
        runBlocking { rig.engine.syncAll(SyncMode.MANUAL) }
        assertEquals(2, rig.api.getRunRequests.size)
    }

    @Test
    fun stopped_builds_are_only_rechecked_slowly_in_the_background() {
        val rig = MonitorRig()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now(), runId = 1, stopped = true)) }
        rig.api.runAnswers += ghError(Kind.NOT_FOUND, "still gone")
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        rig.clock.nowMillis += 60_000
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(1, rig.api.getRunRequests.size)
        rig.clock.nowMillis += 10 * 60_000
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(2, rig.api.getRunRequests.size)
    }

    @Test
    fun network_loss_ends_the_pass_early_and_reconnecting_completes_it_with_one_notification() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        runBlocking {
            rig.store.saveBuild(record("a", BuildStatus.RUNNING, t - 2000, runId = 1))
            rig.store.saveBuild(record("b", BuildStatus.RUNNING, t - 1000, runId = 2))
        }
        // Offline: the first request fails; the second build is not even tried.
        rig.api.runAnswersById[1] = answers(ghError(Kind.NETWORK), run(1, "completed", "success"))
        rig.api.runAnswersById[2] = answers(run(2, "completed", "success"))
        val offline = runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertTrue(offline.transientFailure)
        assertEquals(listOf(1L), rig.api.getRunRequests)
        assertTrue(rig.sink.posted.none { it.kind.terminal })
        assertTrue(runBlocking { rig.store.getBuildHistory() }.none { it.pollingStopped })

        // Back online: a failed attempt did not count as a check, so the retry is not held back by the gap.
        val online = runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertFalse(online.transientFailure)
        assertEquals(2, online.finished)
        assertEquals(1, rig.sink.kinds("a").count { it == "success" })
        assertEquals(1, rig.sink.kinds("b").count { it == "success" })
        // A third pass has nothing left to do and asks GitHub nothing.
        val before = rig.api.getRunRequests.size
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(before, rig.api.getRunRequests.size)
    }

    @Test
    fun a_rate_limit_ends_the_pass_and_reports_how_long_to_wait() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        runBlocking {
            rig.store.saveBuild(record("a", BuildStatus.RUNNING, t - 2000, runId = 1))
            rig.store.saveBuild(record("b", BuildStatus.RUNNING, t - 1000, runId = 2))
        }
        rig.api.runAnswers += ghError(Kind.RATE_LIMITED, retryAt = t + 90_000)
        val report = runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertTrue(report.rateLimited)
        assertEquals(90_000L, report.retryAfterMillis)
        assertEquals(1, rig.api.getRunRequests.size)
    }

    @Test
    fun without_a_sign_in_every_build_is_marked_stopped_with_the_reconnect_hint() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        runBlocking {
            rig.store.saveBuild(record("a", BuildStatus.RUNNING, t - 2000, runId = 1))
            rig.store.saveBuild(record("b", BuildStatus.RUNNING, t - 1000, runId = 2))
        }
        rig.api.runAnswers += ghError(Kind.NOT_CONNECTED)
        val report = runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertTrue(report.authProblem)
        assertEquals(2, report.stopped)
        assertEquals(0, report.snapshot.watchable)
        assertEquals(2, report.snapshot.monitorable)
    }

    @Test
    fun no_more_than_the_configured_number_of_builds_are_checked_in_one_pass() {
        val rig = MonitorRig(policy = com.almus.appcreatorpro.service.monitor.BackgroundSyncPolicy(maxBuildsPerRun = 2))
        runBlocking {
            for (i in 1..5) rig.store.saveBuild(record("b$i", BuildStatus.RUNNING, rig.clock.now() - i * 1000L, runId = i.toLong()))
        }
        rig.api.runAnswers += run(1, "in_progress")
        runBlocking { rig.engine.syncAll(SyncMode.BACKGROUND) }
        assertEquals(2, rig.api.getRunRequests.size)
    }

    @Test
    fun application_restart_recovers_an_unfinished_build_from_disk_and_completes_it() {
        val dir = tmp.newFolder("builds")
        val t = 10_000_000L
        // Before the "restart": the app saved a running build and was killed.
        val before = MonitorRig(store = FileBuildStore(dir, kotlinx.coroutines.Dispatchers.Unconfined))
        runBlocking { before.store.saveBuild(record("a", BuildStatus.RUNNING, t - 120_000, runId = 7, project = "Shop")) }

        // After the restart: brand new objects over the same folder, GitHub says the run finished meanwhile.
        val after = MonitorRig(store = FileBuildStore(dir, kotlinx.coroutines.Dispatchers.Unconfined))
        after.api.runAnswers += run(7, "completed", "success")
        val restored = runBlocking { after.store.getBuildHistory() }
        assertEquals(BuildStatus.RUNNING, restored.single().status)
        assertEquals(7L, restored.single().runId)

        // Live following is resumed for the unfinished build ...
        after.monitor.recoverOnStart()
        assertEquals(listOf("a"), after.follower.tracked)
        // ... and the background pass (WorkManager after a restart) reaches the final state on its own.
        val outcome = runBlocking { after.monitor.runBackgroundSync(slot = 0, attempt = 0) }
        assertEquals(com.almus.appcreatorpro.service.monitor.BackgroundOutcome.DONE, outcome)
        assertEquals(BuildStatus.SUCCESS, runBlocking { after.store.getBuild("a")!! }.status)
        // The record on disk is the synchronised one: a third start sees the same final state.
        val third = FileBuildStore(dir, kotlinx.coroutines.Dispatchers.Unconfined)
        assertEquals(BuildStatus.SUCCESS, runBlocking { third.getBuild("a")!!.status })
        assertEquals(1, after.sink.kinds("a").count { it == "success" })
    }

    @Test
    fun recovery_looks_at_stopped_builds_and_resumes_live_following_for_running_ones() {
        val rig = MonitorRig()
        runBlocking {
            rig.store.saveBuild(record("stopped", BuildStatus.RUNNING, rig.clock.now(), runId = 1, stopped = true))
            rig.store.saveBuild(record("normal", BuildStatus.RUNNING, rig.clock.now(), runId = 2))
        }
        rig.api.runAnswersById[1] = answers(run(1, "in_progress"))
        rig.monitor.recoverOnStart()
        assertEquals(listOf(1L), rig.api.getRunRequests)
        assertFalse(runBlocking { rig.store.getBuild("stopped")!! }.pollingStopped)
        assertEquals(setOf("stopped", "normal"), rig.follower.tracked.toSet())
    }

    @Test
    fun refresh_now_checks_everything_resumes_following_and_explains_the_result() {
        val rig = MonitorRig()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now(), runId = 1, stopped = true)) }
        rig.api.runAnswers += run(1, "completed", "success")
        val report = runBlocking { rig.monitor.refreshNow() }
        assertEquals("1 build finished.", report.userMessage())
        assertTrue(rig.follower.tracked.isEmpty())
    }

    @Test
    fun the_refresh_messages_are_honest_about_trouble() {
        val rig = MonitorRig()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now(), runId = 1)) }
        rig.api.runAnswers += ghError(Kind.NETWORK)
        assertTrue(runBlocking { rig.monitor.refreshNow() }.userMessage().contains("Could not reach GitHub"))
        rig.api.runAnswers.clear(); rig.api.runAnswers += ghError(Kind.TOKEN_REJECTED)
        assertTrue(runBlocking { rig.monitor.refreshNow() }.userMessage().contains("Reconnect GitHub"))
        val empty = MonitorRig()
        assertEquals("No builds are running.", runBlocking { empty.monitor.refreshNow() }.userMessage())
    }
}
