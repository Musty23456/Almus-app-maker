package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.BuildStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the background monitor and the dashboard need from the in-app tracker. */
interface BuildFollower {
    /** Ids of the builds that are being followed live in this process. */
    val activeBuildIds: StateFlow<Set<String>>

    /** Starts following [buildId]; does nothing if it is already followed. */
    fun track(buildId: String)
}

/**
 * Keeps following builds in an app-wide [scope], so a build keeps being tracked while the user moves between
 * screens. At most one tracking job runs per build.
 */
class BuildTracker(
    private val orchestrator: CloudBuildOrchestrator,
    private val builds: BuildStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : BuildFollower {
    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val _activeBuildIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the builds that are being followed right now. */
    override val activeBuildIds: StateFlow<Set<String>> = _activeBuildIds.asStateFlow()

    /** Uploads the project and starts the build, then follows it in the background. */
    suspend fun startBuild(
        project: Project,
        config: CloudBuildConfig,
        buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT,
    ): StartResult {
        val result = orchestrator.start(project, config, buildConfig)
        if (result is StartResult.Started) track(result.record.id)
        return result
    }

    /** Starts following [buildId] (also resumes a build whose checking had stopped). Does nothing if already followed. */
    override fun track(buildId: String) {
        synchronized(lock) {
            if (jobs[buildId]?.isActive == true) return
            _activeBuildIds.update { it + buildId }
            // LAZY: the job is registered before it can run, so even an instantly finishing build cleans up correctly.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    orchestrator.follow(buildId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    markStopped(buildId)
                } finally {
                    synchronized(lock) {
                        jobs.remove(buildId)
                        _activeBuildIds.update { it - buildId }
                    }
                }
            }
            jobs[buildId] = job
            job.start()
        }
    }

    /** Asks GitHub to cancel [record]'s run, then keeps following it so the real end is recorded. Returns a user-safe message. */
    suspend fun cancel(record: BuildRecord): String {
        val message = orchestrator.cancel(record)
        track(record.id)
        return message
    }

    /**
     * Called once when the app starts: follows again every real build that was still unfinished when the app was
     * closed. Builds whose checking was deliberately stopped (timeout, expired sign-in) are left for the user to refresh.
     */
    fun resumeUnfinished() {
        scope.launch {
            try {
                builds.getBuildHistory()
                    .filter { !it.isSample && !it.status.isTerminal && !it.pollingStopped }
                    .forEach { track(it.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Opening a build screen resumes tracking anyway.
            }
        }
    }

    private suspend fun markStopped(buildId: String) {
        try {
            val record = builds.getBuild(buildId) ?: return
            if (record.status.isTerminal) return
            builds.saveBuild(
                record.copy(
                    pollingStopped = true,
                    detail = "Checking stopped unexpectedly. Tap refresh to try again.",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Nothing more can be done; the next refresh starts tracking again.
        }
    }
}
