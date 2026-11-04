package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.github.GitHubApi
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GradleErrors
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.github.toVisibleInfos
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.repository.BuildStore
import kotlinx.coroutines.CancellationException

/**
 * Reads the steps, artifacts and logs of a build from GitHub when the user opens its status page. Problems with
 * one part (for example the artifact list) do not hide the other parts; only when nothing could be read is the
 * whole result a failure.
 */
class BuildDetailsService(
    private val api: GitHubApi,
    private val builds: BuildStore,
) {

    suspend fun load(buildId: String, includeLog: Boolean): DetailsResult {
        val record = builds.getBuild(buildId)
            ?: return DetailsResult.Failed("This build is no longer in the history.", retryable = false)
        val coordinates = splitRepo(record.repoFullName)
            ?: return DetailsResult.Failed("This build has no repository information.", retryable = false)
        val runId = record.runId
            ?: return DetailsResult.Failed("GitHub has not started a run for this build yet.", retryable = true)
        val (owner, repo) = coordinates

        var jobs: List<WorkflowJob> = emptyList()
        var jobsError: GitHubException? = null
        try {
            jobs = api.listJobs(owner, repo, runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            jobsError = e
        }

        var artifacts: List<BuildArtifactInfo> = record.artifacts
        var artifactsNote: String? = null
        var artifactsError: GitHubException? = null
        try {
            val fresh = api.listArtifacts(owner, repo, runId).toVisibleInfos()
            artifacts = fresh
            rememberArtifacts(record, fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            if (e.kind == GitHubException.Kind.NOT_FOUND) {
                val gone = record.artifacts.map { if (it.status == ArtifactStatus.AVAILABLE) it.copy(status = ArtifactStatus.DELETED) else it }
                artifacts = gone
                artifactsNote = "GitHub no longer has this run, so its artifacts cannot be downloaded."
                rememberArtifacts(record, gone)
            } else {
                artifactsError = e
                artifactsNote = "The artifacts could not be checked: ${e.message.orEmpty()}"
            }
        }

        val jobsFailure = jobsError
        val artifactsFailure = artifactsError
        if (jobsFailure != null && artifactsFailure != null) {
            val retryable = !jobsFailure.isAuthProblem &&
                (jobsFailure.isTransient || jobsFailure.kind == GitHubException.Kind.RATE_LIMITED)
            return DetailsResult.Failed(
                jobsFailure.message.orEmpty().ifBlank { "Could not load the build details. Try again." },
                retryable,
            )
        }

        val log = if (includeLog && jobsFailure == null) logFor(owner, repo, jobs) else null
        return DetailsResult.Loaded(
            BuildDetails(
                jobs = jobs,
                jobsNote = jobsFailure?.let { "The steps could not be loaded: ${it.message.orEmpty()}" },
                failedSteps = BuildDetailsSupport.failedSteps(jobs),
                artifacts = artifacts,
                artifactsNote = artifactsNote,
                log = log,
            ),
        )
    }

    /** The end of the build log (for any build, also successful ones). Never throws except when cancelled. */
    suspend fun loadLog(buildId: String): BuildLogView {
        val record = builds.getBuild(buildId)
            ?: return BuildLogView(null, emptyList(), "This build is no longer in the history.")
        val coordinates = splitRepo(record.repoFullName)
            ?: return BuildLogView(null, emptyList(), "This build has no repository information.")
        val runId = record.runId
            ?: return BuildLogView(null, emptyList(), "GitHub has not started a run for this build yet.")
        return try {
            logFor(coordinates.first, coordinates.second, api.listJobs(coordinates.first, coordinates.second, runId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            BuildLogView(null, emptyList(), "The log could not be loaded: ${e.message.orEmpty()}")
        }
    }

    private suspend fun logFor(owner: String, repo: String, jobs: List<WorkflowJob>): BuildLogView {
        val job = BuildDetailsSupport.pickLogJob(jobs)
            ?: return BuildLogView(null, emptyList(), "GitHub has not listed a job for this run yet.")
        return try {
            val raw = api.getJobLogTail(owner, repo, job.id, BuildDetailsSupport.LOG_TAIL_CHARS)
            if (raw.isBlank()) {
                BuildLogView(
                    null,
                    emptyList(),
                    "GitHub returned an empty log for \"${job.name}\". Logs can take a moment to appear; try again shortly.",
                )
            } else {
                BuildLogView(GradleErrors.cleanTail(raw), GradleErrors.extract(raw), null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            BuildLogView(null, emptyList(), "The log could not be loaded: ${e.message.orEmpty()}")
        }
    }

    /** Keeps the history entry in step with GitHub, but only for finished builds (a running build is still changing). */
    private suspend fun rememberArtifacts(record: BuildRecord, fresh: List<BuildArtifactInfo>) {
        if (!record.status.isTerminal || fresh == record.artifacts) return
        try {
            builds.saveBuild(
                record.copy(
                    artifacts = fresh,
                    artifactNames = fresh.filter { it.status == ArtifactStatus.AVAILABLE }.map { it.name },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The history entry is only a cache of GitHub's state.
        }
    }
}
