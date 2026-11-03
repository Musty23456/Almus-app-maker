package com.almus.appcreatorpro.service.monitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.almus.appcreatorpro.AlmusApplication

/**
 * The WorkManager job. It only checks GitHub and updates the local records and notifications: the build itself runs
 * on GitHub Actions. All decisions live in [BuildMonitor.runBackgroundSync].
 */
class BuildSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? AlmusApplication)?.container ?: return Result.failure()
        val slot = inputData.getInt(KEY_SLOT, 0)
        return when (container.buildMonitor.runBackgroundSync(slot, runAttemptCount)) {
            BackgroundOutcome.RETRY -> Result.retry()
            BackgroundOutcome.DONE -> Result.success()
        }
    }

    companion object {
        const val KEY_SLOT = "slot"
    }
}
