package com.almus.appcreatorpro.service.monitor

import com.almus.appcreatorpro.domain.model.BuildRecord

/** Which builds a synchronisation pass looks at. */
enum class SyncMode {
    /** Periodic background pass: builds that are being watched, plus stopped ones at a slow rate. */
    BACKGROUND,

    /** App start: only builds whose checking had stopped (the in-app tracker resumes all the others by itself). */
    RECOVER_STOPPED,

    /** The user pressed refresh: every unfinished build, no waiting between checks. */
    MANUAL,
}

/**
 * How often and how much the background monitor talks to GitHub. Defaults are deliberately gentle: a build is checked
 * often only while it is young, and a quiet build is checked rarely.
 */
data class BackgroundSyncPolicy(
    /** Never check the same build again sooner than this (several triggers can fire together). */
    val minGapBetweenChecksMs: Long = 30_000L,
    /** A build whose checking had stopped is looked at again at most this often. */
    val stoppedRecheckGapMs: Long = 10 * 60_000L,
    /** Delay before the first background check of a new build. */
    val firstCheckDelayMs: Long = 60_000L,
    val earlyDelayMs: Long = 60_000L,
    val earlyPhaseMs: Long = 10 * 60_000L,
    val midDelayMs: Long = 2 * 60_000L,
    val midPhaseMs: Long = 30 * 60_000L,
    val lateDelayMs: Long = 5 * 60_000L,
    /** GitHub stops a job after 6 hours; builds older than this are no longer watched in the background. */
    val maxWatchMs: Long = 8 * 3_600_000L,
    /** Upper bound of builds checked in one pass (one GitHub request each, plus the details of a finished one). */
    val maxBuildsPerRun: Int = 10,
    /** After this many failed attempts in a row the worker stops asking WorkManager for a retry and waits for its next turn. */
    val maxWorkerAttempts: Int = 4,
    /** Never wait longer than this for a rate limit before the next check. */
    val rateLimitMaxDelayMs: Long = 15 * 60_000L,
) {
    /** Time to wait before the next check, given how long the youngest watched build has been running. */
    fun delayFor(ageMs: Long): Long = when {
        ageMs < earlyPhaseMs -> earlyDelayMs
        ageMs < midPhaseMs -> midDelayMs
        else -> lateDelayMs
    }

    /** Counts what the monitor has to look after. Sample builds and finished builds never count. */
    fun snapshot(records: List<BuildRecord>, nowMillis: Long): MonitorSnapshot {
        val live = records.filter { !it.isSample && !it.status.isTerminal && nowMillis - it.startedAtMillis < maxWatchMs }
        val watched = live.filter { !it.pollingStopped }
        return MonitorSnapshot(
            watchable = watched.size,
            monitorable = live.size,
            youngestWatchableAgeMs = watched.maxOfOrNull { it.startedAtMillis }?.let { (nowMillis - it).coerceAtLeast(0L) },
        )
    }
}

/**
 * [watchable] = unfinished builds that are being followed normally; [monitorable] = those plus builds whose checking
 * stopped (they are rechecked slowly by the 15-minute watchdog only).
 */
data class MonitorSnapshot(
    val watchable: Int = 0,
    val monitorable: Int = 0,
    val youngestWatchableAgeMs: Long? = null,
)

/** What one synchronisation pass did. */
data class SyncReport(
    val checked: Int = 0,
    val updated: Int = 0,
    val finished: Int = 0,
    val skipped: Int = 0,
    val stopped: Int = 0,
    /** No connection or GitHub server trouble: the pass ended early and should be repeated later. */
    val transientFailure: Boolean = false,
    val rateLimited: Boolean = false,
    /** How long GitHub asked us to wait (rate limit), if it said. */
    val retryAfterMillis: Long? = null,
    val authProblem: Boolean = false,
    val snapshot: MonitorSnapshot = MonitorSnapshot(),
) {
    /** One sentence for the refresh button. */
    fun userMessage(): String = when {
        authProblem -> "Your GitHub sign-in is no longer valid. Reconnect GitHub to check build status."
        rateLimited -> "GitHub's rate limit has been reached. Try again in a few minutes."
        transientFailure -> "Could not reach GitHub. Check your connection and try again."
        finished == 1 -> "1 build finished."
        finished > 1 -> "$finished builds finished."
        updated > 0 -> "Build status updated."
        snapshot.monitorable == 0 -> "No builds are running."
        else -> "Builds are up to date."
    }
}

/** What the worker should tell WorkManager. */
enum class BackgroundOutcome { DONE, RETRY }
