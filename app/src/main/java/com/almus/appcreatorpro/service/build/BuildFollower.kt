package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.model.BuildRecord
import kotlinx.coroutines.flow.StateFlow

/** Something that follows builds live while the app is open. The background monitor skips builds it is following. */
interface BuildFollower {
    /** Ids of the builds that are being followed right now. */
    val activeBuildIds: StateFlow<Set<String>>

    /** Starts following [buildId] (nothing happens if it is already followed or has finished). */
    fun track(buildId: String)
}

/** The result of ONE status check of ONE build ([CloudBuildOrchestrator.syncOnce]). */
sealed interface SyncOutcome {
    /** Nothing to check: unknown, sample or finished build. */
    data object Skipped : SyncOutcome

    /** GitHub answered and nothing changed. */
    data class Unchanged(val record: BuildRecord) : SyncOutcome

    /** GitHub answered and the saved record changed, but the build is still going. */
    data class Progressed(val record: BuildRecord) : SyncOutcome

    /** The build reached a final state; the record holds the result. */
    data class Finished(val record: BuildRecord) : SyncOutcome

    /** Checking was given up (too old, run unknown, no permission). The record says why. */
    data class Stopped(val record: BuildRecord, val reason: String) : SyncOutcome

    /** The GitHub sign-in is gone. Checking stops until the user reconnects. */
    data class AuthProblem(val record: BuildRecord) : SyncOutcome

    /** GitHub asked us to slow down. Nothing was changed. */
    data class RateLimited(val retryAtMillis: Long?) : SyncOutcome

    /** Connection trouble. Nothing was changed, so the check can simply be repeated. */
    data class TransientError(val message: String) : SyncOutcome
}
