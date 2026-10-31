package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.github.CloudBuildConfig
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

/**
 * Keeps following builds in an app-wide [scope], so a build keeps being tracked while the user moves between
 * screens. At most one tracking job runs per build.
 */
class BuildTracker(
    private val orchestrator: CloudBuildOrchestrator,
    private val builds: BuildStore,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val jobs = HashMap<String, Job>()
    private val _activeBuildIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of the builds that are being followed right now. */
    val activeBuildIds: StateFlow<Set<String>> = _activeBuildIds.asStateFlow()

    /** Uploads the project and starts the build, then follows it in the background. */
    suspend fun startBuild(project: Project, config: CloudBuildConfig): StartResult {
        val result = orchestrator.start(project, config)
        if (result is StartResult.Started) track(result.record.id)
        return result
    }

    /** Starts following [buildId] (also resumes a build whose checking had stopped). Does nothing if already followed. */
    fun track(buildId: String) {
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
