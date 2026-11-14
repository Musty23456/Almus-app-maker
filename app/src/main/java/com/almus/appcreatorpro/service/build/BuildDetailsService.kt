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

/** Loads the steps, artifacts and log of one build from GitHub on demand. */
class BuildDetailsService(
    private val api: GitHubApi,
    private val builds: BuildStore,
) {
    suspend fun load(buildId: String, includeLog: Boolean): DetailsResult {
        val record = builds.getBuild(buildId) ?: return DetailsResult.Failed("This build is not in the history.", false)
        val runId = record.runId
        val repo = split(record)
        if (runId == null || repo == null) {
            return DetailsResult.Failed("GitHub has not started this run yet. Try again in a moment.", true)
        }
        try {
            val jobs = api.listJobs(repo.first, repo.second, runId)
            var artifactsNote: String? = null
            val artifacts = try {
                api.listArtifacts(repo.first, repo.second, runId).toVisibleInfos().also { fresh ->
                    if (fresh != record.artifacts) {
                        builds.saveBuild(record.copy(artifacts = fresh, artifactNames = fresh.map { it.name }))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException) {
                artifactsNote = "GitHub could not list the artifacts: ${e.message.orEmpty()}"
                record.artifacts
            }
            val failed = jobs.flatMap { job ->
                if (job.conclusion != "failure") emptyList()
                else job.steps.filter { it.conclusion == "failure" }.map { FailedStep(job.name, it.name) }
            }
            val log = if (includeLog) loadLogFor(record, jobs) else null
            return DetailsResult.Loaded(
                BuildDetails(
                    jobs = jobs,
                    jobsNote = if (jobs.isEmpty() && !record.status.isTerminal) "GitHub has not listed any steps yet." else null,
                    failedSteps = failed,
                    artifacts = artifacts,
                    artifactsNote = artifactsNote,
                    log = log,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            return DetailsResult.Failed(
                e.message ?: "GitHub could not be reached.",
                retryable = e.isTransient || e.kind == GitHubException.Kind.RATE_LIMITED,
            )
        }
    }

    /** The end of the build log, cleaned of secrets, plus the errors found in it. Never throws (except cancellation). */
    suspend fun loadLog(buildId: String): BuildLogView {
        val record = builds.getBuild(buildId) ?: return BuildLogView(null, emptyList(), "This build is not in the history.")
        val repo = split(record)
        val runId = record.runId
        if (repo == null || runId == null) return BuildLogView(null, emptyList(), "GitHub has not started this run yet.")
        return try {
            loadLogFor(record, api.listJobs(repo.first, repo.second, runId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            BuildLogView(null, emptyList(), "The log could not be loaded: ${e.message.orEmpty()}")
        }
    }

    /** The (redacted) log for the AI debugger and the error analyser. */
    suspend fun loadRawLog(buildId: String): RawBuildLog {
        val record = builds.getBuild(buildId) ?: return RawBuildLog(null, "This build is not in the history.")
        val repo = split(record)
        val runId = record.runId
        if (repo == null || runId == null) return RawBuildLog(null, "GitHub has not started this run yet.")
        return try {
            val jobs = api.listJobs(repo.first, repo.second, runId)
            val job = pickJob(jobs) ?: return RawBuildLog(null, "GitHub has no job log for this build yet.")
            val raw = api.getJobLogTail(repo.first, repo.second, job.id, RAW_LOG_CHARS)
            RawBuildLog(GradleErrors.cleanTail(raw, maxLines = 4_000, maxChars = RAW_LOG_CHARS), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            RawBuildLog(null, "The log could not be loaded: ${e.message.orEmpty()}")
        }
    }

    private suspend fun loadLogFor(record: BuildRecord, jobs: List<WorkflowJob>): BuildLogView {
        val repo = split(record) ?: return BuildLogView(null, emptyList(), "The repository of this build is unknown.")
        val job = pickJob(jobs) ?: return BuildLogView(null, emptyList(), "GitHub has no job log for this build yet.")
        return try {
            val raw = api.getJobLogTail(repo.first, repo.second, job.id, LOG_CHARS)
            BuildLogView(
                text = GradleErrors.cleanTail(raw).ifBlank { null },
                errors = GradleErrors.extract(raw),
                note = if (raw.isBlank()) "GitHub returned an empty log." else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            BuildLogView(null, emptyList(), "The log could not be loaded: ${e.message.orEmpty()}")
        }
    }

    /** The job that failed, otherwise the last one. */
    private fun pickJob(jobs: List<WorkflowJob>): WorkflowJob? =
        jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.lastOrNull()

    private fun split(record: BuildRecord): Pair<String, String>? {
        val parts = record.repoFullName?.split('/', limit = 2) ?: return null
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
    }

    private companion object {
        const val LOG_CHARS = 60_000
        const val RAW_LOG_CHARS = 200_000
    }
}
