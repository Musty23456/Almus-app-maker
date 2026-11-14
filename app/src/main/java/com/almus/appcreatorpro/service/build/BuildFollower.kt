package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.model.BuildRecord
import kotlinx.coroutines.flow.StateFlow

/** Follows unfinished builds live while the app is open. The background monitor skips builds that are followed here. */
interface BuildFollower {
    /** Ids of the builds that are being followed right now. */
    val activeBuildIds: StateFlow<Set<String>>

    /** Starts following [buildId] (does nothing if it is already followed or already finished). */
    fun track(buildId: String)
}

/** The result of ONE status check of ONE build ([CloudBuildOrchestrator.syncOnce]). */
sealed interface SyncOutcome {
    /** The build reached a final state and was saved. */
    data class Finished(val record: BuildRecord) : SyncOutcome

    /** The state changed (for example queued -> running) and was saved. */
    data class Progressed(val record: BuildRecord) : SyncOutcome

    /** Nothing new. */
    data class Unchanged(val record: BuildRecord) : SyncOutcome

    /** Nothing to check: unknown build, sample build or already finished. */
    data object Skipped : SyncOutcome

    /** Checking was given up for this build (too old, run gone...). The record is marked as stopped. */
    data class Stopped(val record: BuildRecord?, val reason: String) : SyncOutcome

    /** The GitHub sign-in is missing or rejected. The record is marked as stopped with a "Reconnect GitHub" hint. */
    data class AuthProblem(val record: BuildRecord?) : SyncOutcome

    /** GitHub's rate limit; [retryAtMillis] is when it resets, if GitHub said so. Nothing was changed. */
    data class RateLimited(val retryAtMillis: Long?) : SyncOutcome

    /** Connection trouble or a server error. Nothing was changed, the check can simply be repeated. */
    data class TransientError(val message: String) : SyncOutcome
}
