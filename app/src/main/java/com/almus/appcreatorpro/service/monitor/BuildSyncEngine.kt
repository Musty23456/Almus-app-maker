package com.almus.appcreatorpro.service.monitor

import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.service.build.CloudBuildOrchestrator
import com.almus.appcreatorpro.service.build.SyncOutcome
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One synchronisation pass over the unfinished builds: ONE GitHub request per build ([CloudBuildOrchestrator.syncOnce]),
 * results saved to the local store, notifications updated. It never waits or loops, so it fits a WorkManager job.
 *
 * Request economy: builds the in-app tracker is already following live are skipped; a build is not checked again within
 * [BackgroundSyncPolicy.minGapBetweenChecksMs]; a pass ends at the first connection failure or rate limit instead of
 * repeating the same failing request for every build.
 */
class BuildSyncEngine(
    private val store: BuildStore,
    private val orchestrator: CloudBuildOrchestrator,
    private val notifications: BuildNotificationCoordinator,
    private val state: BuildSyncStateStore,
    private val followedInApp: () -> Set<String>,
    private val policy: BackgroundSyncPolicy = BackgroundSyncPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Two triggers at the same moment (worker and refresh button) take turns instead of racing on the same records. */
    private val mutex = Mutex()

    suspend fun syncAll(mode: SyncMode): SyncReport = mutex.withLock {
        val followed = followedInApp()
        val startedAt = now()
        val candidates = store.getBuildHistory()
            .filter { isCandidate(it, mode, followed, startedAt) }
            .sortedBy { it.startedAtMillis }
            .take(policy.maxBuildsPerRun)

        var report = SyncReport()
        for (candidate in candidates) {
            val outcome = orchestrator.syncOnce(candidate.id, if (mode == SyncMode.MANUAL) Long.MAX_VALUE else policy.maxWatchMs)
            // A failed attempt must not count as a check, or the retry would be refused by the minimum gap.
            if (outcome !is SyncOutcome.TransientError && outcome !is SyncOutcome.RateLimited) {
                state.markChecked(candidate.id, now())
            }
            when (outcome) {
                is SyncOutcome.Finished -> report = report.copy(checked = report.checked + 1, finished = report.finished + 1)
                is SyncOutcome.Progressed -> report = report.copy(checked = report.checked + 1, updated = report.updated + 1)
                is SyncOutcome.Unchanged -> report = report.copy(checked = report.checked + 1)
                SyncOutcome.Skipped -> report = report.copy(skipped = report.skipped + 1)
                is SyncOutcome.Stopped -> report = report.copy(checked = report.checked + 1, stopped = report.stopped + 1)
                is SyncOutcome.AuthProblem ->
                    report = report.copy(checked = report.checked + 1, stopped = report.stopped + 1, authProblem = true)
                is SyncOutcome.RateLimited -> {
                    report = report.copy(
                        rateLimited = true,
                        retryAfterMillis = outcome.retryAtMillis?.let { (it - now()).coerceAtLeast(0L) },
                    )
                    break
                }
                is SyncOutcome.TransientError -> {
                    report = report.copy(transientFailure = true)
                    break
                }
            }
        }

        // Announce what changed before returning, so a worker that is stopped right afterwards has already done it.
        val after = store.getBuildHistory()
        notifications.process(after)
        report.copy(snapshot = policy.snapshot(after, now()))
    }

    private fun isCandidate(record: BuildRecord, mode: SyncMode, followed: Set<String>, nowMillis: Long): Boolean {
        if (record.isSample || record.status.isTerminal) return false
        if (record.id in followed) return false
        val age = nowMillis - record.startedAtMillis
        val last = state.lastCheckedAt(record.id)
        fun gapPassed(gap: Long) = last == null || nowMillis - last >= gap
        return when (mode) {
            SyncMode.BACKGROUND ->
                age < policy.maxWatchMs &&
                    gapPassed(if (record.pollingStopped) policy.stoppedRecheckGapMs else policy.minGapBetweenChecksMs)

            SyncMode.RECOVER_STOPPED ->
                record.pollingStopped && age < policy.maxWatchMs && gapPassed(policy.minGapBetweenChecksMs)

            SyncMode.MANUAL -> true
        }
    }
}
