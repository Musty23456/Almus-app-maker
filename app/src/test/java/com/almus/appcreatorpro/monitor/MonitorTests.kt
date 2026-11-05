package com.almus.appcreatorpro.monitor

import com.almus.appcreatorpro.domain.github.GitHubException.Kind
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.monitor.BackgroundOutcome
import com.almus.appcreatorpro.service.monitor.BackgroundSyncPolicy
import com.almus.appcreatorpro.testutil.ghError
import com.almus.appcreatorpro.testutil.run
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundSyncPolicyTest {
    private val p = BackgroundSyncPolicy()

    @Test
    fun young_builds_are_checked_often_and_old_ones_rarely() {
        assertEquals(60_000L, p.delayFor(0))
        assertEquals(60_000L, p.delayFor(9 * 60_000L))
        assertEquals(120_000L, p.delayFor(10 * 60_000L))
        assertEquals(120_000L, p.delayFor(29 * 60_000L))
        assertEquals(300_000L, p.delayFor(30 * 60_000L))
        assertEquals(300_000L, p.delayFor(5 * 3_600_000L))
    }

    @Test
    fun the_snapshot_counts_only_real_unfinished_builds_that_are_not_too_old() {
        val now = 100_000_000L
        val records = listOf(
            record("run", BuildStatus.RUNNING, now - 1000),
            record("stopped", BuildStatus.RUNNING, now - 5000, stopped = true),
            record("done", BuildStatus.SUCCESS, now - 5000),
            record("sample", BuildStatus.RUNNING, now - 5000).copy(isSample = true),
            record("ancient", BuildStatus.RUNNING, now - 9 * 3_600_000L),
        )
        val s = p.snapshot(records, now)
        assertEquals(1, s.watchable)
        assertEquals(2, s.monitorable)
        assertEquals(1000L, s.youngestWatchableAgeMs)
    }

    @Test
    fun android_work_manager_limits_are_respected_by_the_defaults() {
        // Periodic work cannot run more often than every 15 minutes; the watchdog uses exactly that. The chain's delays
        // are one-time delays and never below the gap that keeps duplicate requests away.
        assertTrue(p.earlyDelayMs >= p.minGapBetweenChecksMs)
        assertTrue(p.maxWorkerAttempts in 1..10)
        assertTrue(p.maxWatchMs < 24 * 3_600_000L)
    }
}

class BuildMonitorSchedulingTest {

    @Test
    fun a_new_unfinished_build_starts_the_watchdog_and_the_chain_once() {
        val rig = MonitorRig()
        rig.monitor.start()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.PREPARING, rig.clock.now(), runId = null)) }
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.QUEUED, rig.clock.now(), runId = 5)) }
        runBlocking { rig.store.saveBuild(record("b", BuildStatus.QUEUED, rig.clock.now(), runId = 6)) }
        assertEquals(listOf("cancelAll", "watchdog", "chain:60000"), rig.scheduler.events)
    }

    @Test
    fun when_every_build_has_finished_the_schedule_is_cancelled() {
        val rig = MonitorRig()
        rig.monitor.start()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now())) }
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.SUCCESS, rig.clock.now(), finishedAt = rig.clock.now())) }
        assertEquals("cancelAll", rig.scheduler.events.last())
    }

    @Test
    fun at_app_start_an_unfinished_build_from_the_last_session_gets_monitoring_back() {
        val rig = MonitorRig()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now())) }
        rig.monitor.start()
        assertEquals(listOf("watchdog", "chain:60000"), rig.scheduler.events)
    }

    @Test
    fun only_stopped_builds_left_means_only_the_slow_watchdog() {
        val rig = MonitorRig()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, rig.clock.now(), stopped = true)) }
        rig.monitor.start()
        assertEquals(listOf("watchdog"), rig.scheduler.events)
    }

    @Test
    fun every_change_the_in_app_tracker_saves_also_updates_the_notifications() {
        val rig = MonitorRig()
        rig.monitor.start()
        val t = rig.clock.now()
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.QUEUED, t)) }
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.RUNNING, t)) }
        runBlocking { rig.store.saveBuild(record("a", BuildStatus.FAILED, t, finishedAt = t)) }
        assertEquals(listOf("started", "in_progress", "failed"), rig.sink.kinds("a"))
    }

    @Test
    fun turning_notifications_off_removes_the_ones_on_screen() {
        val rig = MonitorRig()
        rig.monitor.start()
        val before = rig.sink.cancelAllCalls
        rig.settings.setNotificationsEnabled(false)
        assertEquals(before + 1, rig.sink.cancelAllCalls)
    }
}

class BuildMonitorBackgroundPassTest {

    private fun rigWith(vararg records: com.almus.appcreatorpro.domain.model.BuildRecord): MonitorRig {
        val rig = MonitorRig()
        runBlocking { records.forEach { rig.store.saveBuild(it) } }
        return rig
    }

    @Test
    fun while_a_build_runs_the_next_check_is_scheduled_on_the_other_slot() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 5 * 60_000, runId = 1))
        rig.api.runAnswers += run(1, "in_progress")
        assertEquals(BackgroundOutcome.DONE, runBlocking { rig.monitor.runBackgroundSync(slot = 0, attempt = 0) })
        assertEquals(listOf("continue:0:60000"), rig.scheduler.events)
    }

    @Test
    fun the_pace_slows_down_as_the_build_gets_older() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 45 * 60_000, runId = 1))
        rig.api.runAnswers += run(1, "in_progress")
        runBlocking { rig.monitor.runBackgroundSync(1, 0) }
        assertEquals("continue:1:300000", rig.scheduler.events.single())
    }

    @Test
    fun when_the_build_finishes_the_chain_ends_and_the_watchdog_is_cancelled() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.api.runAnswers += run(1, "completed", "success")
        runBlocking { rig.monitor.runBackgroundSync(0, 0) }
        assertEquals(listOf("cancelWatchdog"), rig.scheduler.events)
        assertEquals(1, rig.sink.kinds("a").count { it == "success" })
    }

    @Test
    fun a_lost_connection_asks_workmanager_for_a_retry_with_backoff_and_changes_no_schedule() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.api.runAnswers += ghError(Kind.NETWORK)
        assertEquals(BackgroundOutcome.RETRY, runBlocking { rig.monitor.runBackgroundSync(0, 0) })
        assertEquals(BackgroundOutcome.RETRY, runBlocking { rig.monitor.runBackgroundSync(0, 3) })
        assertTrue(rig.scheduler.events.isEmpty())
    }

    @Test
    fun after_too_many_retries_the_pass_ends_and_the_chain_continues_at_its_normal_pace() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.api.runAnswers += ghError(Kind.NETWORK)
        assertEquals(BackgroundOutcome.DONE, runBlocking { rig.monitor.runBackgroundSync(0, 4) })
        assertEquals(listOf("continue:0:60000"), rig.scheduler.events)
    }

    @Test
    fun reconnecting_after_a_retry_finishes_the_build_and_notifies_once() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.api.runAnswersById[1] = answers(ghError(Kind.NETWORK), run(1, "completed", "success"))
        assertEquals(BackgroundOutcome.RETRY, runBlocking { rig.monitor.runBackgroundSync(0, 0) })
        assertEquals(BackgroundOutcome.DONE, runBlocking { rig.monitor.runBackgroundSync(0, 1) })
        assertEquals(1, rig.sink.kinds("a").count { it == "success" })
    }

    @Test
    fun a_rate_limit_delays_the_next_check_until_github_allows_it_but_never_too_long() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.api.runAnswers += ghError(Kind.RATE_LIMITED, retryAt = 10_000_000L + 5 * 60_000)
        assertEquals(BackgroundOutcome.DONE, runBlocking { rig.monitor.runBackgroundSync(0, 0) })
        assertEquals(300_000L, rig.scheduler.lastContinueDelay)

        val far = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        far.api.runAnswers += ghError(Kind.RATE_LIMITED, retryAt = 10_000_000L + 3 * 3_600_000L)
        runBlocking { far.monitor.runBackgroundSync(0, 0) }
        assertEquals(15 * 60_000L, far.scheduler.lastContinueDelay)
    }

    @Test
    fun a_lost_sign_in_ends_the_chain_instead_of_hammering_github() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.api.runAnswers += ghError(Kind.TOKEN_REJECTED)
        assertEquals(BackgroundOutcome.DONE, runBlocking { rig.monitor.runBackgroundSync(0, 0) })
        assertTrue(rig.scheduler.events.none { it.startsWith("continue") })
        assertTrue(runBlocking { rig.store.getBuild("a")!! }.pollingStopped)
    }

    @Test
    fun an_unexpected_failure_is_retried_a_few_times_and_never_silently_kills_the_chain() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.api.runAnswers += RuntimeException("bug") // not a GitHubException: syncOnce reports a transient error
        assertEquals(BackgroundOutcome.RETRY, runBlocking { rig.monitor.runBackgroundSync(0, 0) })
        assertFalse(rig.scheduler.events.any { it == "cancelAll" })
    }

    @Test
    fun builds_the_app_follows_live_cost_no_requests_in_the_background() {
        val rig = rigWith(record("a", BuildStatus.RUNNING, 10_000_000L - 60_000, runId = 1))
        rig.follower.setFollowed(setOf("a"))
        runBlocking { rig.monitor.runBackgroundSync(0, 0) }
        assertTrue(rig.api.getRunRequests.isEmpty())
        // The chain still continues: if the app is killed, the next pass takes over.
        assertEquals(listOf("continue:0:60000"), rig.scheduler.events)
    }
}
