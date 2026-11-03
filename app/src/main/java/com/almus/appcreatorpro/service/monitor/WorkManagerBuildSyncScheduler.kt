package com.almus.appcreatorpro.service.monitor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Schedules [BuildSyncWorker] with WorkManager. WorkManager keeps the schedule across app restarts and reboots and
 * decides when the work really runs (Doze, App Standby and battery limits are respected: every delay here is a
 * minimum, not a promise). Every request needs a network connection; none asks for an exemption from battery rules.
 *
 *  - Watchdog: one periodic job (the 15-minute minimum WorkManager allows), only while an unfinished build exists.
 *  - Chain: while a build is running, one-time jobs that each schedule the next one. Two alternating names are used
 *    so a running job never replaces itself.
 *  - Retry: a worker that returns "retry" is run again by WorkManager with exponential backoff.
 */
class WorkManagerBuildSyncScheduler(context: Context) : BuildSyncScheduler {
    private val appContext = context.applicationContext
    private val workManager: WorkManager get() = WorkManager.getInstance(appContext)

    private val constraints: Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun ensureWatchdog() {
        val request = PeriodicWorkRequestBuilder<BuildSyncWorker>(WATCHDOG_MINUTES, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(BuildSyncWorker.KEY_SLOT to 0))
            .build()
        workManager.enqueueUniquePeriodicWork(WATCHDOG_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun ensureChain(delayMs: Long) {
        enqueueChain(slot = 0, delayMs = delayMs, policy = ExistingWorkPolicy.KEEP)
    }

    override fun continueChain(afterSlot: Int, delayMs: Long) {
        enqueueChain(slot = 1 - afterSlot.coerceIn(0, 1), delayMs = delayMs, policy = ExistingWorkPolicy.REPLACE)
    }

    override fun cancelWatchdog() {
        workManager.cancelUniqueWork(WATCHDOG_NAME)
    }

    override fun cancelAll() {
        workManager.cancelUniqueWork(WATCHDOG_NAME)
        workManager.cancelUniqueWork(chainName(0))
        workManager.cancelUniqueWork(chainName(1))
    }

    private fun enqueueChain(slot: Int, delayMs: Long, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<BuildSyncWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .setInputData(workDataOf(BuildSyncWorker.KEY_SLOT to slot))
            .build()
        workManager.enqueueUniqueWork(chainName(slot), policy, request)
    }

    private fun chainName(slot: Int) = "$CHAIN_PREFIX$slot"

    private companion object {
        const val WATCHDOG_NAME = "almus-build-sync-watchdog"
        const val CHAIN_PREFIX = "almus-build-sync-chain-"
        const val WATCHDOG_MINUTES = 15L
        const val BACKOFF_SECONDS = 30L
    }
}
