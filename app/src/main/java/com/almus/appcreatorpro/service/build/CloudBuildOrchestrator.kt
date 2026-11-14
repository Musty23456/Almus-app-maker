package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildConfigurationValidator
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.build.SigningReadiness
import com.almus.appcreatorpro.domain.build.WorkflowInputs
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import com.almus.appcreatorpro.domain.github.CloudBuildInputValidator
import com.almus.appcreatorpro.domain.github.CloudBuildValidator
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubApi
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.LogExcerpt
import com.almus.appcreatorpro.domain.github.PollingPolicy
import com.almus.appcreatorpro.domain.github.ValidationReport
import com.almus.appcreatorpro.domain.github.WorkflowRun
import com.almus.appcreatorpro.domain.github.toVisibleInfos
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Starts, follows and finishes cloud builds. It talks only to [GitHubApi]; it never sees a token, a keystore or a
 * password (release signing happens inside GitHub Actions, with secrets the user stored there).
 */
class CloudBuildOrchestrator(
    private val api: GitHubApi,
    private val files: SourceFileRepository,
    private val builds: BuildStore,
    private val signing: SigningReadiness? = null,
    private val policy: PollingPolicy = PollingPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val newId: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
) {

    private class Analysis(val result: PreflightResult, val files: Map<String, String>, val workflowVersion: Int)

    // ------------------------------------------------------------------------------------------ preflight

    /** Read-only: checks the project, the repository, the branch and the workflow. Uploads nothing. */
    suspend fun preflight(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT): PreflightResult =
        analyse(project, config, buildConfig).result

    private suspend fun analyse(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration): Analysis {
        val blockers = ArrayList<String>()
        val (projectFiles, readProblems) = readProjectFiles(project)
        blockers += readProblems
        val validation = CloudBuildValidator.validate(project.type, projectFiles)
        blockers += validation.errors

        CloudBuildInputValidator.validateDirectory(config.projectDir)?.let { blockers += it }
        CloudBuildInputValidator.validateGradleVersion(config.gradleVersion)?.let { blockers += it }
        CloudBuildInputValidator.validateBranchName(config.branch)?.let { blockers += it }
        blockers += BuildConfigurationValidator.validate(buildConfig)
        blockers += signing?.blockers(buildConfig, config.repoFullName).orEmpty()

        var repo: GitHubRepo? = null
        var branch: GitHubBranch? = null
        var commit: CommitSummary? = null
        var workflow: WorkflowCheck? = null
        var workflowVersion = 0
        try {
            repo = api.getRepository(config.repoOwner, config.repoName)
            if (!repo.canPush) blockers += "You only have read access to ${repo.fullName}, so the project cannot be uploaded there."
            branch = api.getBranch(config.repoOwner, config.repoName, config.branch)
            commit = api.getLatestCommit(config.repoOwner, config.repoName, config.branch)
            val text = api.getFileText(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_PATH, config.branch)
            val version = text?.let { CloudBuildConfig.workflowVersionOf(it) }
            workflowVersion = version ?: 0
            workflow = when {
                text == null -> WorkflowCheck.MISSING
                version == null || version < CloudBuildConfig.MIN_WORKFLOW_VERSION -> WorkflowCheck.OUTDATED
                config.branch != repo.defaultBranch &&
                    api.getFileText(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_PATH, repo.defaultBranch) == null ->
                    WorkflowCheck.NOT_ON_DEFAULT_BRANCH
                version < CloudBuildConfig.WORKFLOW_VERSION -> WorkflowCheck.LEGACY
                else -> WorkflowCheck.OK
            }
            when (workflow) {
                WorkflowCheck.MISSING -> blockers += "The workflow file ${CloudBuildConfig.WORKFLOW_PATH} is missing on branch \"${config.branch}\". Add it once (use Copy workflow)."
                WorkflowCheck.OUTDATED -> blockers += "The workflow file in the repository is too old or damaged. Replace it with the current one (use Copy workflow)."
                WorkflowCheck.NOT_ON_DEFAULT_BRANCH -> blockers += "GitHub only starts a workflow that also exists on the default branch (\"${repo.defaultBranch}\"). Add the workflow file there too."
                WorkflowCheck.LEGACY ->
                    if (needsCurrentWorkflow(buildConfig)) {
                        blockers += "The workflow file in the repository is an older version that only builds a plain debug APK. Replace it with the current one (use Copy workflow) to build release, AAB or custom builds."
                    }
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            blockers += e.message ?: "GitHub could not be reached."
        }
        val result = PreflightResult(validation, repo, branch, commit, workflow, blockers.distinct())
        return Analysis(result, projectFiles, workflowVersion)
    }

    private fun needsCurrentWorkflow(c: BuildConfiguration): Boolean =
        c.variant != BuildVariant.DEBUG_APK || c.applicationId != null || c.versionName != null || c.versionCode != null ||
            c.minSdk != null || c.targetSdk != null || c.options.minify != null || c.options.shrinkResources != null

    private suspend fun readProjectFiles(project: Project): Pair<Map<String, String>, List<String>> {
        val problems = ArrayList<String>()
        val map = LinkedHashMap<String, String>()
        try {
            for (entry in files.listFiles(project.id)) {
                try {
                    map[entry.path] = files.readFile(project.id, entry.path)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    problems += "The file \"${entry.path}\" could not be read."
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            problems += "The project files could not be read."
        }
        return map to problems
    }

    // ------------------------------------------------------------------------------------------ start

    /**
     * Checks everything, uploads the project in ONE commit and starts the workflow. Nothing is uploaded and no history
     * entry is made when the check fails ([StartResult.Rejected]). GitHub errors after that point are thrown.
     */
    suspend fun start(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT): StartResult {
        val analysis = analyse(project, config, buildConfig)
        if (!analysis.result.canBuild) return StartResult.Rejected(analysis.result.blockers)

        val id = newId()
        val startedAt = now()
        var record = BuildRecord(
            id = id,
            projectName = project.name,
            status = BuildStatus.PREPARING,
            startedAtMillis = startedAt,
            durationSeconds = null,
            summary = "${config.repoFullName} (${config.branch})",
            isSample = false,
            projectId = project.id,
            repoFullName = config.repoFullName,
            branch = config.branch,
            projectDir = config.projectDir,
            detail = "Uploading the project to GitHub...",
            configuration = buildConfig,
        )
        builds.saveBuild(record)

        val commit = try {
            api.commitDirectory(
                config.repoOwner, config.repoName, config.branch,
                "Almus cloud build $id: ${project.name}", config.projectDir, analysis.files,
            )
        } catch (e: CancellationException) {
            builds.deleteBuild(id)
            throw e
        } catch (e: Exception) {
            builds.deleteBuild(id) // nothing reached GitHub, so no history entry
            throw e
        }
        record = record.copy(commitSha = commit.commitSha, detail = "Starting the build on GitHub...")
        builds.saveBuild(record)

        val inputs = WorkflowInputs.build(config.projectDir, id, config.gradleVersion, buildConfig, analysis.workflowVersion)
        val dispatchedRunId = try {
            api.dispatchWorkflow(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_FILE, config.branch, inputs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failed = record.copy(
                status = BuildStatus.FAILED,
                detail = "The project was uploaded, but GitHub did not start the build: ${e.message.orEmpty()}",
                finishedAtMillis = now(),
                durationSeconds = ((now() - startedAt) / 1000).toInt(),
            )
            builds.saveBuild(failed)
            throw e
        }

        var run: WorkflowRun? = null
        if (dispatchedRunId == null) {
            // Older GitHub behaviour: the run id is not returned, so the run is found by the id in its title.
            for (attempt in 1..FIND_ATTEMPTS_AT_START) {
                run = try {
                    api.findRun(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_FILE, config.branch, id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubException) {
                    null
                }
                if (run != null) break
                if (attempt < FIND_ATTEMPTS_AT_START) sleep(policy.findRunIntervalMs)
            }
        }
        val runId = dispatchedRunId ?: run?.id
        record = record.copy(
            status = BuildStatus.QUEUED,
            runId = runId,
            runUrl = run?.htmlUrl ?: runId?.let { "https://github.com/${config.repoFullName}/actions/runs/$it" },
            detail = if (runId == null) "Waiting for GitHub to start the run..." else null,
        )
        builds.saveBuild(record)
        return StartResult.Started(record)
    }

    // ------------------------------------------------------------------------------------------ cancel

    /** Asks GitHub to cancel the build. The real end is seen by following the run. */
    suspend fun cancel(record: BuildRecord): Result<String> {
        if (record.status.isTerminal) return Result.failure(IllegalStateException("This build has already finished."))
        val runId = record.runId
        val (owner, name) = splitRepo(record)
            ?: return Result.failure(IllegalStateException("The repository of this build is unknown."))
        if (runId == null) {
            builds.saveBuild(record.copy(cancelRequested = true, detail = "Cancel requested. It is sent as soon as GitHub starts the run."))
            return Result.success("Cancel noted. It will be sent as soon as GitHub starts the run.")
        }
        return try {
            api.cancelRun(owner, name, runId)
            builds.saveBuild(record.copy(cancelRequested = true, detail = "Cancel requested. Waiting for GitHub to stop the run..."))
            Result.success("Cancel sent to GitHub. The build stops in a moment.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            Result.failure(IllegalStateException(e.message))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("The cancel request could not be sent."))
        }
    }

    // ------------------------------------------------------------------------------------------ following

    /** Checks the build again and again until it is finished or checking has to stop. */
    suspend fun follow(buildId: String) {
        var interval = policy.initialIntervalMs
        var errors = 0
        while (true) {
            when (val outcome = syncOnce(buildId, policy.overallTimeoutMs)) {
                is SyncOutcome.Finished, is SyncOutcome.Stopped, is SyncOutcome.AuthProblem, SyncOutcome.Skipped -> return
                is SyncOutcome.Progressed -> {
                    errors = 0
                    sleep(interval)
                    interval = (interval * 3 / 2).coerceAtMost(policy.maxIntervalMs)
                }
                is SyncOutcome.Unchanged -> {
                    errors = 0
                    sleep(interval)
                    interval = (interval * 3 / 2).coerceAtMost(policy.maxIntervalMs)
                }
                is SyncOutcome.RateLimited -> {
                    val wait = outcome.retryAtMillis?.let { (it - now()).coerceAtLeast(1_000L) } ?: policy.maxIntervalMs
                    if (wait > policy.maxRateLimitWaitMs) {
                        markStopped(buildId, "GitHub's rate limit is reached. Tap \"Check GitHub now\" later to continue.")
                        return
                    }
                    sleep(wait)
                }
                is SyncOutcome.TransientError -> {
                    errors++
                    if (errors >= policy.maxConsecutiveErrors) {
                        markStopped(buildId, "Lost the connection to GitHub. Tap \"Check GitHub now\" to continue.")
                        return
                    }
                    sleep((policy.errorBackoffBaseMs shl (errors - 1)).coerceAtMost(policy.maxIntervalMs))
                }
            }
        }
    }

    private suspend fun markStopped(buildId: String, detail: String): BuildRecord? {
        val r = builds.getBuild(buildId) ?: return null
        if (r.status.isTerminal) return r
        val stopped = r.copy(pollingStopped = true, detail = detail)
        builds.saveBuild(stopped)
        return stopped
    }

    // ------------------------------------------------------------------------------------------ one status check

    /**
     * ONE status check of one build: locate the run if needed, ask GitHub once, save what changed. It never waits and
     * never loops, so the background worker can call it too. [maxAgeMs] is how old a build may be before checking stops.
     */
    suspend fun syncOnce(buildId: String, maxAgeMs: Long): SyncOutcome {
        val record = builds.getBuild(buildId) ?: return SyncOutcome.Skipped
        if (record.isSample || record.status.isTerminal) return SyncOutcome.Skipped
        val t = now()
        if (t - record.startedAtMillis > maxAgeMs) {
            return stop(record, "Stopped checking: this build is too old. Open the run on GitHub to see how it ended.")
        }
        val (owner, name) = splitRepo(record) ?: return stop(record, "The repository of this build is unknown, so it cannot be checked.")

        try {
            var current = record
            val run: WorkflowRun
            val runId = record.runId
            if (runId == null) {
                val branch = record.branch ?: return stop(record, "The branch of this build is unknown, so it cannot be checked.")
                val found = api.findRun(owner, name, CloudBuildConfig.WORKFLOW_FILE, branch, record.id)
                if (found == null) {
                    return if (t - record.startedAtMillis > policy.findRunTimeoutMs) {
                        stop(record, "GitHub did not start the run. Open the repository's Actions page to check.")
                    } else {
                        SyncOutcome.Unchanged(record)
                    }
                }
                current = record.copy(runId = found.id, runUrl = found.htmlUrl, commitSha = record.commitSha ?: found.headSha)
                if (record.cancelRequested) {
                    // The user asked to cancel before the run existed: send it now, once.
                    try {
                        api.cancelRun(owner, name, found.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: GitHubException) {
                        // The run is still followed; the user can cancel again.
                    }
                    current = current.copy(cancelRequested = false, detail = "Cancel sent to GitHub.")
                }
                run = found
            } else {
                run = api.getRun(owner, name, runId)
            }

            val status = run.mappedStatus
            if (status.isTerminal) return SyncOutcome.Finished(finish(current, run, owner, name))

            val resumed = current.pollingStopped
            val updated = current.copy(
                status = if (status == BuildStatus.PREPARING) BuildStatus.QUEUED else status,
                runUrl = run.htmlUrl,
                pollingStopped = false,
                detail = if (resumed) null else current.detail,
            )
            return if (updated == record) {
                SyncOutcome.Unchanged(record)
            } else {
                builds.saveBuild(updated)
                SyncOutcome.Progressed(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            return when {
                e.isAuthProblem -> {
                    val stopped = record.copy(pollingStopped = true, detail = "Not signed in to GitHub. Reconnect GitHub, then tap \"Check GitHub now\".")
                    builds.saveBuild(stopped)
                    SyncOutcome.AuthProblem(stopped)
                }
                e.kind == GitHubException.Kind.RATE_LIMITED -> SyncOutcome.RateLimited(e.retryAtMillis)
                e.isTransient -> SyncOutcome.TransientError(e.message ?: "Connection problem.")
                else -> stop(record, "GitHub cannot report this build any more: ${e.message.orEmpty()}")
            }
        }
    }

    private suspend fun stop(record: BuildRecord, reason: String): SyncOutcome {
        val stopped = record.copy(pollingStopped = true, detail = reason)
        builds.saveBuild(stopped)
        return SyncOutcome.Stopped(stopped, reason)
    }

    private suspend fun finish(record: BuildRecord, run: WorkflowRun, owner: String, name: String): BuildRecord {
        val finishedAt = now()
        val status = run.mappedStatus
        val artifacts = try {
            api.listArtifacts(owner, name, run.id).toVisibleInfos()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            record.artifacts
        }
        var excerpt: String? = null
        var detail: String? = when (status) {
            BuildStatus.SUCCESS -> null
            BuildStatus.CANCELLED -> "The build was cancelled."
            else -> "The build failed."
        }
        if (status == BuildStatus.FAILED) {
            try {
                val jobs = api.listJobs(owner, name, run.id)
                val failedJob = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.lastOrNull()
                val failedStep = failedJob?.steps?.firstOrNull { it.conclusion == "failure" }
                if (failedStep != null) detail = "Failed at \"${failedStep.name}\"."
                if (failedJob != null) {
                    excerpt = LogExcerpt.extract(api.getJobLogTail(owner, name, failedJob.id, EXCERPT_LOG_CHARS)).ifBlank { null }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The excerpt is a convenience: the build result does not depend on it.
            }
        }
        val finished = record.copy(
            status = status,
            runUrl = run.htmlUrl,
            durationSeconds = ((finishedAt - record.startedAtMillis) / 1000).toInt().coerceAtLeast(0),
            finishedAtMillis = finishedAt,
            artifacts = artifacts,
            artifactNames = artifacts.map { it.name },
            errorExcerpt = excerpt,
            detail = detail,
            pollingStopped = false,
            cancelRequested = false,
        )
        builds.saveBuild(finished)
        return finished
    }

    private fun splitRepo(record: BuildRecord): Pair<String, String>? {
        val parts = record.repoFullName?.split('/', limit = 2) ?: return null
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
    }

    private companion object {
        const val FIND_ATTEMPTS_AT_START = 3
        const val EXCERPT_LOG_CHARS = 60_000
    }
}
