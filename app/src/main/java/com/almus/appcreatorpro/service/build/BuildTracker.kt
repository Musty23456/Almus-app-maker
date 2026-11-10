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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Starts builds and follows them live in the app's own scope, so a build keeps being followed while the user moves
 * between screens. Each build is followed at most once at a time.
 */
class BuildTracker(
    private val orchestrator: CloudBuildOrchestrator,
    private val builds: BuildStore,
    private val scope: CoroutineScope,
    @Suppress("unused") private val now: () -> Long = System::currentTimeMillis,
) : BuildFollower {

    private val active = MutableStateFlow<Set<String>>(emptySet())
    override val activeBuildIds: StateFlow<Set<String>> = active.asStateFlow()

    override fun track(buildId: String) {
        var added = false
        active.update {
            added = buildId !in it
            if (added) it + buildId else it
        }
        if (!added) return
        scope.launch {
            try {
                orchestrator.follow(buildId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The record keeps its last saved state; the user can refresh.
            } finally {
                active.update { it - buildId }
            }
        }
    }

    /** Uploads and starts a build, then follows it. */
    suspend fun startBuild(
        project: Project,
        config: CloudBuildConfig,
        buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT,
    ): StartResult {
        val result = orchestrator.start(project, config, buildConfig)
        if (result is StartResult.Started) track(result.record.id)
        return result
    }

    /** Asks GitHub to cancel [record]'s run and keeps following it to see the real end. */
    suspend fun cancel(record: BuildRecord): Result<String> {
        val result = orchestrator.cancel(record)
        if (result.isSuccess) track(record.id)
        return result
    }

    /** Called when the app starts: builds that were still going when the app was closed are followed again. */
    fun resumeUnfinished() {
        scope.launch {
            builds.getBuildHistory()
                .filter { !it.isSample && !it.status.isTerminal && !it.pollingStopped }
                .forEach { track(it.id) }
        }
    }
}
