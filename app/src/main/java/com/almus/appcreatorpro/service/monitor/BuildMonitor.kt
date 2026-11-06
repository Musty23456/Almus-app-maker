package com.almus.appcreatorpro.service.monitor

import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.build.BuildFollower
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What the dashboard's refresh button needs. */
fun interface BuildRefresher {
    suspend fun refreshNow(): SyncReport
}

/**
 * Ties the pieces together:
 *  - while the process is alive it watches the build store, so every change (from the in-app tracker or a sync) updates
 *    the notifications and the WorkManager schedule;
 *  - it recovers builds after a restart;
 *  - it runs the WorkManager passes and decides how they continue (retry with backoff, next link of the chain, stop).
 */
class BuildMonitor(
    private val store: BuildStore,
    private val engine: BuildSyncEngine,
    private val notifications: BuildNotificationCoordinator,
    private val settings: MonitorSettingsStore,
    private val sink: NotificationSink,
    private val scheduler: BuildSyncScheduler,
    private val follower: BuildFollower,
    private val scope: CoroutineScope,
    private val policy: BackgroundSyncPolicy = BackgroundSyncPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
) : BuildRefresher {

    private enum class Plan { NONE, WATCHDOG_ONLY, ACTIVE }

    private val lock = Any()
    private var started = false
    private var lastPlan: Plan? = null

    /** Starts watching the store. Safe to call more than once. */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            try {
                store.observeBuilds().collect { records -> onRecords(records) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Watching stops for this run only; it starts again at the next app start.
            }
        }
        scope.launch {
            try {
                // Turning notifications off removes the ones already on screen.
                settings.notificationsEnabled.collect { enabled -> if (!enabled) sink.cancelAll() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never let a notification problem close the app.
            }
        }
    }

    private fun onRecords(records: List<BuildRecord>) {
        try {
            notifications.process(records)
        } catch (e: Exception) {
            // Notifications must never break build tracking.
        }
        try {
            applyPlan(policy.snapshot(records, now()))
        } catch (e: Exception) {
            // A scheduling problem must never crash the app; the next change of a build tries again.
        }
    }

    private fun applyPlan(snapshot: MonitorSnapshot) {
        val plan = when {
            snapshot.watchable > 0 -> Plan.ACTIVE
            snapshot.monitorable > 0 -> Plan.WATCHDOG_ONLY
            else -> Plan.NONE
        }
        synchronized(lock) {
            if (plan == lastPlan) return
            lastPlan = plan
        }
        when (plan) {
            Plan.ACTIVE -> {
                scheduler.ensureWatchdog()
                scheduler.ensureChain(policy.firstCheckDelayMs)
            }
            Plan.WATCHDOG_ONLY -> scheduler.ensureWatchdog()
            Plan.NONE -> scheduler.cancelAll()
        }
    }

    // ------------------------------------------------------------------------------------------ recovery

    /**
     * Called once when the app starts. Builds that were still being followed are resumed by the tracker; this looks
     * at the ones whose checking had stopped (connection lost, timeout) with a single request each, and resumes live
     * following for every build that is still running.
     */
    fun recoverOnStart() {
        scope.launch {
            try {
                engine.syncAll(SyncMode.RECOVER_STOPPED)
                resumeFollowing()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The next refresh or background pass tries again.
            }
        }
    }

    /** The refresh button: checks every unfinished build once, then follows the ones that are still running. */
    override suspend fun refreshNow(): SyncReport {
        val report = engine.syncAll(SyncMode.MANUAL)
        resumeFollowing()
        return report
    }

    private suspend fun resumeFollowing() {
        store.getBuildHistory()
            .filter { !it.isSample && !it.status.isTerminal && !it.pollingStopped }
            .forEach { follower.track(it.id) }
    }

    // ------------------------------------------------------------------------------------------ WorkManager pass

    /**
     * One WorkManager pass. [slot] is the chain slot of the worker that runs it, [attempt] how many times this same
     * job has been retried already. Returns [BackgroundOutcome.RETRY] only for connection trouble, and only a few
     * times in a row (WorkManager applies its exponential backoff between the attempts); after that the pass is
     * treated as done and the chain continues at its normal pace.
     */
    suspend fun runBackgroundSync(slot: Int, attempt: Int): BackgroundOutcome {
        val report = try {
            engine.syncAll(SyncMode.BACKGROUND)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt < policy.maxWorkerAttempts) return BackgroundOutcome.RETRY
            // Something unexpected keeps failing: keep the chain alive at its slowest pace, do not give up silently.
            scheduler.continueChain(slot, policy.lateDelayMs)
            return BackgroundOutcome.DONE
        }
        if (report.transientFailure && attempt < policy.maxWorkerAttempts) return BackgroundOutcome.RETRY

        val snapshot = report.snapshot
        when {
            snapshot.monitorable == 0 -> {
                scheduler.cancelWatchdog()
                synchronized(lock) { lastPlan = Plan.NONE }
            }
            snapshot.watchable > 0 -> {
                val wait = report.retryAfterMillis?.coerceIn(policy.minGapBetweenChecksMs, policy.rateLimitMaxDelayMs)
                    ?: policy.delayFor(snapshot.youngestWatchableAgeMs ?: 0L)
                scheduler.continueChain(slot, wait)
            }
            // Only builds whose checking stopped are left: the slow watchdog keeps looking at them.
        }
        return BackgroundOutcome.DONE
    }
}
