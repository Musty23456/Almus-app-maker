package com.almus.appcreatorpro.monitor

import com.almus.appcreatorpro.data.storage.InMemoryKeyValueStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.monitor.BuildNotificationPlanner
import com.almus.appcreatorpro.service.monitor.DeepLinks
import com.almus.appcreatorpro.service.monitor.MonitorSettingsStore
import com.almus.appcreatorpro.service.monitor.NotificationAccessStatus
import com.almus.appcreatorpro.service.monitor.NotificationController
import com.almus.appcreatorpro.service.monitor.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPlannerTest {
    private val now = 10_000_000L

    @Test
    fun each_status_maps_to_one_of_the_five_notifications() {
        assertEquals(NotificationKind.STARTED, BuildNotificationPlanner.kindFor(BuildStatus.PREPARING))
        assertEquals(NotificationKind.STARTED, BuildNotificationPlanner.kindFor(BuildStatus.QUEUED))
        assertEquals(NotificationKind.IN_PROGRESS, BuildNotificationPlanner.kindFor(BuildStatus.RUNNING))
        assertEquals(NotificationKind.SUCCESS, BuildNotificationPlanner.kindFor(BuildStatus.SUCCESS))
        assertEquals(NotificationKind.FAILED, BuildNotificationPlanner.kindFor(BuildStatus.FAILED))
        assertEquals(NotificationKind.CANCELLED, BuildNotificationPlanner.kindFor(BuildStatus.CANCELLED))
    }

    @Test
    fun a_notification_names_project_build_type_status_and_result() {
        val r = record(
            "b1", BuildStatus.FAILED, startedAt = now - 60_000, finishedAt = now - 1_000,
            configuration = BuildConfiguration(BuildVariant.RELEASE_AAB), detail = "The build failed at step \"Sign\".",
        ).copy(durationSeconds = 59)
        val n = BuildNotificationPlanner.plan(r, "in_progress", now)!!.notification!!
        assertTrue(n.title.contains("Shop"))
        assertTrue(n.title.contains("failed"))
        assertTrue(n.text.contains("Release AAB"))
        assertTrue(n.text.contains("Failed"))
        assertTrue(n.bigText.contains("Result: The build failed at step \"Sign\"."))
        assertTrue(n.bigText.contains("Duration: 59s"))
    }

    @Test
    fun builds_from_before_phase_6_are_described_as_debug_apk() {
        val n = BuildNotificationPlanner.plan(record("b1", BuildStatus.QUEUED, startedAt = now), null, now)!!.notification!!
        assertTrue(n.text.contains("Debug APK"))
        assertEquals(NotificationKind.STARTED, n.kind)
        assertTrue(n.bigText.contains("Result: not finished yet"))
    }

    @Test
    fun the_same_stage_is_never_repeated_and_a_build_never_goes_backwards() {
        assertNull(BuildNotificationPlanner.plan(record("b", BuildStatus.QUEUED, startedAt = now), "started", now))
        assertNull(BuildNotificationPlanner.plan(record("b", BuildStatus.QUEUED, startedAt = now), "in_progress", now))
        assertNull(BuildNotificationPlanner.plan(record("b", BuildStatus.RUNNING, startedAt = now), "success", now))
        assertNull(BuildNotificationPlanner.plan(record("b", BuildStatus.FAILED, startedAt = now, finishedAt = now), "success", now))
    }

    @Test
    fun a_build_that_jumps_from_queued_to_success_between_checks_is_announced_once_as_success() {
        val plan = BuildNotificationPlanner.plan(record("b", BuildStatus.SUCCESS, startedAt = now - 5000, finishedAt = now), "started", now)!!
        assertEquals(NotificationKind.SUCCESS, plan.kind)
        assertNotNull(plan.notification)
    }

    @Test
    fun old_results_are_remembered_but_not_announced() {
        val old = record("b", BuildStatus.SUCCESS, startedAt = now - 40L * 3_600_000, finishedAt = now - 39L * 3_600_000)
        val plan = BuildNotificationPlanner.plan(old, null, now)!!
        assertEquals(NotificationKind.SUCCESS, plan.kind)
        assertNull(plan.notification)
    }

    @Test
    fun sample_builds_never_notify() {
        assertNull(BuildNotificationPlanner.plan(record("s").copy(isSample = true), null, now))
    }

    @Test
    fun deep_links_accept_only_build_ids_the_app_could_have_made() {
        assertEquals("abc123def456", DeepLinks.sanitizeBuildId("abc123def456"))
        assertNull(DeepLinks.sanitizeBuildId(null))
        assertNull(DeepLinks.sanitizeBuildId("../../etc"))
        assertNull(DeepLinks.sanitizeBuildId("a b"))
        assertNull(DeepLinks.sanitizeBuildId("x".repeat(65)))
        assertNull(DeepLinks.sanitizeBuildId(""))
    }
}

class NotificationCoordinatorTest {

    @Test
    fun started_in_progress_and_result_are_each_shown_once_even_if_processed_many_times() {
        val rig = MonitorRig()
        val q = record("b1", BuildStatus.QUEUED, startedAt = rig.clock.now())
        repeat(3) { rig.notifications.process(listOf(q)) }
        val run = q.copy(status = BuildStatus.RUNNING)
        repeat(3) { rig.notifications.process(listOf(run)) }
        val done = q.copy(status = BuildStatus.SUCCESS, finishedAtMillis = rig.clock.now())
        repeat(3) { rig.notifications.process(listOf(done)) }
        assertEquals(listOf("started", "in_progress", "success"), rig.sink.kinds("b1"))
    }

    @Test
    fun failed_and_cancelled_results_are_announced() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        rig.notifications.process(listOf(record("f", BuildStatus.FAILED, t, finishedAt = t), record("c", BuildStatus.CANCELLED, t, finishedAt = t)))
        assertEquals(listOf("failed"), rig.sink.kinds("f"))
        assertEquals(listOf("cancelled"), rig.sink.kinds("c"))
    }

    @Test
    fun the_state_survives_a_restart_so_nothing_is_repeated() {
        val stateKv = InMemoryKeyValueStore()
        val first = MonitorRig(stateKv = stateKv)
        val t = first.clock.now()
        val done = record("b", BuildStatus.SUCCESS, t, finishedAt = t)
        first.notifications.process(listOf(done))
        val second = MonitorRig(stateKv = stateKv, clock = first.clock)
        second.notifications.process(listOf(done))
        assertEquals(1, first.sink.posted.size)
        assertEquals(0, second.sink.posted.size)
    }

    @Test
    fun turned_off_nothing_is_shown_and_turning_on_later_does_not_replay_old_events() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        rig.settings.setNotificationsEnabled(false)
        rig.notifications.process(listOf(record("b", BuildStatus.SUCCESS, t, finishedAt = t)))
        assertTrue(rig.sink.posted.isEmpty())
        assertEquals(listOf("b"), rig.sink.cancelled)
        rig.settings.setNotificationsEnabled(true)
        rig.notifications.process(listOf(record("b", BuildStatus.SUCCESS, t, finishedAt = t)))
        assertTrue(rig.sink.posted.isEmpty())
    }

    @Test
    fun without_the_android_permission_nothing_is_shown_and_nothing_crashes() {
        val rig = MonitorRig()
        rig.sink.access = NotificationAccessStatus.PERMISSION_NEEDED
        val t = rig.clock.now()
        rig.notifications.process(listOf(record("b", BuildStatus.QUEUED, t)))
        assertTrue(rig.sink.posted.isEmpty())
        rig.sink.access = NotificationAccessStatus.ENABLED
        rig.notifications.process(listOf(record("b", BuildStatus.RUNNING, t)))
        assertEquals(listOf("in_progress"), rig.sink.kinds("b"))
    }

    @Test
    fun a_failing_sink_is_retried_next_time_instead_of_losing_the_notification() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        val failing = object : com.almus.appcreatorpro.service.monitor.NotificationSink by rig.sink {
            override fun post(notification: com.almus.appcreatorpro.service.monitor.BuildNotification) = throw SecurityException("no")
        }
        val coordinator = com.almus.appcreatorpro.service.monitor.BuildNotificationCoordinator(rig.settings, failing, rig.state, rig.clock::now)
        coordinator.process(listOf(record("b", BuildStatus.SUCCESS, t, finishedAt = t)))
        assertNull(rig.state.lastStage("b"))
        rig.notifications.process(listOf(record("b", BuildStatus.SUCCESS, t, finishedAt = t)))
        assertEquals(listOf("success"), rig.sink.kinds("b"))
    }

    @Test
    fun bookkeeping_of_builds_that_left_the_history_is_dropped() {
        val rig = MonitorRig()
        val t = rig.clock.now()
        rig.notifications.process(listOf(record("a", BuildStatus.QUEUED, t), record("b", BuildStatus.QUEUED, t)))
        rig.notifications.process(listOf(record("a", BuildStatus.QUEUED, t)))
        assertNotNull(rig.state.lastStage("a"))
        assertNull(rig.state.lastStage("b"))
    }
}

class NotificationControllerTest {
    private var status = NotificationAccessStatus.ENABLED
    private val settings = MonitorSettingsStore(InMemoryKeyValueStore())
    private val controller = NotificationController(settings) { status }

    @Test
    fun notifications_are_on_by_default() {
        assertTrue(controller.ui.value.enabled)
        assertTrue(controller.ui.value.working)
    }

    @Test
    fun the_switch_persists_and_turns_notifications_off() {
        assertFalse(controller.setEnabled(false))
        assertFalse(controller.ui.value.working)
        assertFalse(settings.notificationsEnabled.value)
    }

    @Test
    fun the_choice_is_still_there_after_a_restart() {
        val kv = InMemoryKeyValueStore()
        MonitorSettingsStore(kv).setNotificationsEnabled(false)
        assertFalse(MonitorSettingsStore(kv).notificationsEnabled.value)
    }

    @Test
    fun turning_on_without_the_permission_asks_for_it() {
        status = NotificationAccessStatus.PERMISSION_NEEDED
        assertTrue(controller.setEnabled(true))
        assertTrue(controller.ui.value.needsPermission)
    }

    @Test
    fun a_refused_permission_switches_notifications_off_instead_of_nagging() {
        status = NotificationAccessStatus.PERMISSION_NEEDED
        controller.setEnabled(true)
        controller.onPermissionResult(false)
        assertFalse(controller.ui.value.enabled)
        assertFalse(controller.ui.value.needsPermission)
    }

    @Test
    fun a_granted_permission_makes_notifications_work() {
        status = NotificationAccessStatus.PERMISSION_NEEDED
        controller.setEnabled(true)
        status = NotificationAccessStatus.ENABLED
        controller.onPermissionResult(true)
        assertTrue(controller.ui.value.working)
    }

    @Test
    fun blocked_by_android_settings_is_reported_and_follows_a_later_change() {
        status = NotificationAccessStatus.BLOCKED_BY_SYSTEM
        controller.refresh()
        assertTrue(controller.ui.value.blockedBySystem)
        status = NotificationAccessStatus.ENABLED
        controller.refresh()
        assertTrue(controller.ui.value.working)
    }
}
