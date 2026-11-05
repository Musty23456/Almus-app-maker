package com.almus.appcreatorpro.service.monitor

/**
 * Asks the system to run background synchronisation later. The Android implementation uses WorkManager
 * ([WorkManagerBuildSyncScheduler]); tests use a recording fake. WorkManager only schedules the checks: the build
 * itself runs on GitHub.
 */
interface BuildSyncScheduler {
    /** The slow safety net (about every 15 minutes). Keeps one that is already scheduled. */
    fun ensureWatchdog()

    /** The short-interval chain used while a build is running. Keeps one that is already pending. */
    fun ensureChain(delayMs: Long)

    /** Called by a worker that has finished: schedules the next link of the chain. */
    fun continueChain(afterSlot: Int, delayMs: Long)

    /** Stops the watchdog only (a worker uses this when nothing is left to watch). */
    fun cancelWatchdog()

    /** Stops the watchdog and the chain. */
    fun cancelAll()
}
