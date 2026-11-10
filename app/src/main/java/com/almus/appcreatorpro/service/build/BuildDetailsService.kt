package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.debug.RawBuildLog
import com.almus.appcreatorpro.domain.github.GitHubApi
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GradleErrors
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.github.toVisibleInfos
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildStore
import kotlinx.coroutines.CancellationException

/** Loads steps, artifacts and logs of one build from GitHub. Nothing is changed on GitHub or in the saved record. */
class BuildDetailsService(
    private val api: GitHubApi,
    private val builds: BuildStore,
) {
    suspend fun load(buildId: String, includeLog: Boolean): DetailsResult {
        val record = builds.getBuild(buildId) ?: return DetailsResult.Failed("This build was not found.", false)
        val runId = record.runId ?: return DetailsResult.Failed("GitHub has not started this build yet. Try again in a moment.", true)
        val (owner, name) = split(record) ?: return DetailsResult.Failed("This build has no repository.", false)
        return try {
            val jobs = api.listJobs(owner, name, runId)
            val artifacts = api.listArtifacts(owner, name, runId).toVisibleInfos()
            val failed = jobs.flatMap { job ->
                job.steps.filter { it.conclusion == "failure" }.map { FailedStep(job.name, it.name) }
            }
            val log = if (includeLog) logView(owner, name, jobs) else null
            DetailsResult.Loaded(BuildDetails(jobs, null, failed, artifacts, log))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            DetailsResult.Failed(e.message ?: "Could not load the build details.", e.isTransient || e.kind == GitHubException.Kind.RATE_LIMITED)
        }
    }

    /** The cleaned log of a build, also for builds that succeeded. */
    suspend fun loadLog(buildId: String): BuildLogView {
        val record = builds.getBuild(buildId) ?: return BuildLogView(null, emptyList(), "This build was not found.")
        val runId = record.runId ?: return BuildLogView(null, emptyList(), "GitHub has not started this build yet.")
        val (owner, name) = split(record) ?: return BuildLogView(null, emptyList(), "This build has no repository.")
        return try {
            logView(owner, name, api.listJobs(owner, name, runId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            BuildLogView(null, emptyList(), e.message ?: "The log could not be loaded.")
        }
    }

    /** The log for the debug tools: cleaned of colours and secrets, longer than the one shown on the status screen. */
    suspend fun loadRawLog(buildId: String): RawBuildLog {
        val record = builds.getBuild(buildId) ?: return RawBuildLog(null, "This build was not found.")
        val runId = record.runId ?: return RawBuildLog(null, "GitHub has not started this build yet.")
        val (owner, name) = split(record) ?: return RawBuildLog(null, "This build has no repository.")
        return try {
            val jobs = api.listJobs(owner, name, runId)
            val job = pickJob(jobs) ?: return RawBuildLog(null, "GitHub has no log for this build yet.")
            val tail = api.getJobLogTail(owner, name, job.id, RAW_LOG_CHARS)
            RawBuildLog(GradleErrors.cleanTail(tail, maxLines = 4_000, maxChars = RAW_LOG_CHARS), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            RawBuildLog(null, e.message ?: "The log could not be loaded.")
        }
    }

    private suspend fun logView(owner: String, name: String, jobs: List<WorkflowJob>): BuildLogView {
        val job = pickJob(jobs) ?: return BuildLogView(null, emptyList(), "GitHub has no log for this build yet.")
        return try {
            val tail = api.getJobLogTail(owner, name, job.id, LOG_CHARS)
            BuildLogView(GradleErrors.cleanTail(tail), GradleErrors.extract(tail), "Showing the end of the log of \"${job.name}\".")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            BuildLogView(null, emptyList(), e.message ?: "The log could not be loaded.")
        }
    }

    /** The job that failed, otherwise the last one. */
    private fun pickJob(jobs: List<WorkflowJob>): WorkflowJob? = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.lastOrNull()

    private fun split(record: BuildRecord): Pair<String, String>? {
        val parts = record.repoFullName?.split('/') ?: return null
        return if (parts.size == 2) parts[0] to parts[1] else null
    }

    private companion object {
        const val LOG_CHARS = 60_000
        const val RAW_LOG_CHARS = 200_000
    }
}
