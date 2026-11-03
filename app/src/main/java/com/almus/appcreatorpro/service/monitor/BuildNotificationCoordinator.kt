package com.almus.appcreatorpro.service.monitor

import com.almus.appcreatorpro.domain.model.BuildRecord

/**
 * The single place that turns build state into notifications. It is called from every path that can learn about a
 * change (the in-app tracker through the store, the WorkManager sync, app start), and it remembers the last stage shown
 * per build, so each stage is announced once, also across process restarts.
 *
 * When notifications are off or blocked, stages are still remembered (silently): turning them on later does not
 * replay old events.
 */
class BuildNotificationCoordinator(
    private val settings: MonitorSettingsStore,
    private val sink: NotificationSink,
    private val state: BuildSyncStateStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun process(records: List<BuildRecord>) {
        synchronized(lock) {
            val canPost = settings.notificationsEnabled.value && sink.status() == NotificationAccessStatus.ENABLED
            for (record in records) {
                if (record.isSample) continue
                val plan = BuildNotificationPlanner.plan(record, state.lastStage(record.id), now()) ?: continue
                val notification = plan.notification
                try {
                    if (notification != null && canPost) {
                        sink.post(notification)
                    } else if (plan.kind.terminal) {
                        // No result to show: make sure no "in progress" notification stays behind.
                        sink.cancel(record.id)
                    }
                } catch (e: Exception) {
                    // Showing failed (for example the permission was withdrawn a moment ago). Try again next time.
                    continue
                }
                state.setStage(record.id, plan.kind.stage)
            }
            state.forgetAllExcept(records.map { it.id }.toSet())
        }
    }
}
