package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.BuildStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps following builds in an app-wide scope, so a build keeps being tracked while the user moves between screens.
 * One build is followed at most once; one project cannot be started twice at the same moment.
 */
class BuildTracker(
    private val orchestrator: CloudBuildOrchestrator,
    private val builds: BuildStore,
    private val scope: CoroutineScope,
    @Suppress("unused") private val now: () -> Long = System::currentTimeMillis,
) : BuildFollower {

    private val lock = Any()
    private val active = MutableStateFlow<Set<String>>(emptySet())
    private val starting = HashSet<String>()

    override val activeBuildIds: StateFlow<Set<String>> = active.asStateFlow()

    override fun track(buildId: String) {
        synchronized(lock) {
            if (buildId in active.value) return
            active.value = active.value + buildId
        }
        scope.launch {
            try {
                orchestrator.follow(buildId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A bug or a storage problem must not kill the app-wide scope; the next refresh checks again.
            } finally {
                synchronized(lock) { active.value = active.value - buildId }
            }
        }
    }

    /** Uploads, starts and then follows the build. A second start of the same project while one is starting is refused. */
    suspend fun startBuild(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT): StartResult {
        synchronized(lock) {
            if (!starting.add(project.id)) return StartResult.Rejected(listOf("A build of this project is already starting."))
        }
        try {
            val result = orchestrator.start(project, config, buildConfig)
            if (result is StartResult.Started) track(result.record.id)
            return result
        } finally {
            synchronized(lock) { starting.remove(project.id) }
        }
    }

    /** Asks GitHub to cancel, and follows the build so the real end is recorded. */
    suspend fun cancel(record: BuildRecord): Result<String> {
        val result = orchestrator.cancel(record)
        if (result.isSuccess) {
            val fresh = builds.getBuild(record.id)
            if (fresh != null && !fresh.status.isTerminal) track(record.id)
        }
        return result
    }
}
