package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildConfigurationValidator
import com.almus.appcreatorpro.domain.build.SigningReadiness
import com.almus.appcreatorpro.domain.build.WorkflowInputs
import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
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
 * Runs a cloud build on GitHub Actions: checks the project, uploads it in one commit, starts the workflow and then
 * follows the run. All GitHub access goes through [GitHubApi]; time ([now], [sleep]) is injectable so tests run instantly.
 */
class CloudBuildOrchestrator(
    private val api: GitHubApi,
    private val files: SourceFileRepository,
    private val builds: BuildStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val signing: SigningReadiness? = null,
    private val policy: PollingPolicy = PollingPolicy(),
) {

    // ------------------------------------------------------------------ check and start

    /** Read-only check of project, repository, branch and workflow. Uploads nothing. */
    suspend fun preflight(
        project: Project,
        config: CloudBuildConfig,
        buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT,
    ): PreflightResult {
        val validation = CloudBuildValidator.validate(project.type, readFiles(project))
        val blockers = ArrayList(localBlockers(validation, config, buildConfig))
        val remote = inspectRemote(config, buildConfig)
        blockers += remote.blockers
        return PreflightResult(validation, remote.repo, remote.branch, remote.commit, remote.workflow, blockers.distinct())
    }

    /** Checks, uploads the project in ONE commit and starts the workflow. Rejections upload nothing and create no history. */
    suspend fun start(
        project: Project,
        config: CloudBuildConfig,
        buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT,
    ): StartResult {
        val fileMap = readFiles(project)
        val validation = CloudBuildValidator.validate(project.type, fileMap)
        val localProblems = localBlockers(validation, config, buildConfig)
        if (localProblems.isNotEmpty()) return StartResult.Rejected(localProblems)

        val remote = inspectRemote(config, buildConfig)
        if (remote.blockers.isNotEmpty()) return StartResult.Rejected(remote.blockers)

        val dir = config.projectDir.ifBlank { CloudBuildConfig.defaultDir(project.name) }
        val buildId = UUID.randomUUID().toString().replace("-", "").take(12)
        var record = BuildRecord(
            id = buildId,
            projectName = project.name,
            status = BuildStatus.PREPARING,
            startedAtMillis = now(),
            durationSeconds = null,
            summary = "${config.repoFullName} (${config.branch})",
            isSample = false,
            projectId = project.id,
            repoFullName = config.repoFullName,
            branch = config.branch,
            projectDir = dir,
            configuration = buildConfig,
        )
        builds.saveBuild(record)
        try {
            val commit = api.commitDirectory(
                config.repoOwner, config.repoName, config.branch, "Almus build $buildId: ${project.name}", dir, fileMap,
            )
            record = record.copy(commitSha = commit.commitSha)
            builds.saveBuild(record)

            val workflowVersion = if (remote.workflow == WorkflowCheck.LEGACY) CloudBuildConfig.MIN_WORKFLOW_VERSION else CloudBuildConfig.WORKFLOW_VERSION
            val inputs = WorkflowInputs.build(dir, buildId, config.gradleVersion, buildConfig, workflowVersion)
            val runId = api.dispatchWorkflow(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_FILE, config.branch, inputs)
            record = record.copy(status = BuildStatus.QUEUED, runId = runId)
            builds.saveBuild(record)
            return StartResult.Started(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val finished = now()
            builds.saveBuild(
                record.copy(
                    status = BuildStatus.FAILED,
                    finishedAtMillis = finished,
                    durationSeconds = secondsSince(record.startedAtMillis, finished),
                    detail = "The build could not be started: ${e.message ?: "unknown problem"}",
                ),
            )
            throw e
        }
    }

    // ------------------------------------------------------------------ cancel

    /** Asks GitHub to cancel the run. The caller keeps following the run to see the real end. */
    suspend fun cancel(record: BuildRecord): Result<String> {
        val current = builds.getBuild(record.id) ?: record
        if (current.status.isTerminal) return Result.failure(IllegalStateException("This build has already finished."))
        val (owner, name) = splitRepo(current.repoFullName)
            ?: return Result.failure(IllegalStateException("This build has no repository."))
        val runId = current.runId
        if (runId == null) {
            builds.saveBuild(current.copy(cancelRequested = true, detail = "Cancel requested. It is sent as soon as GitHub starts the run."))
            return Result.success("Cancel noted. It is sent as soon as GitHub starts the run.")
        }
        return try {
            api.cancelRun(owner, name, runId)
            builds.saveBuild(current.copy(cancelRequested = true, detail = "Cancel requested. Waiting for GitHub to stop the run."))
            Result.success("Cancel sent to GitHub. The build stops in a moment.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            Result.failure(IllegalStateException(e.message ?: "The build could not be cancelled."))
        }
    }

    // ------------------------------------------------------------------ following

    /** Follows a build until it finishes or checking has to stop. Used by [BuildTracker]. */
    suspend fun follow(buildId: String) {
        var interval = policy.initialIntervalMs
        var errors = 0
        while (true) {
            when (val outcome = syncOnce(buildId, policy.overallTimeoutMs)) {
                is SyncOutcome.Finished, is SyncOutcome.Stopped, is SyncOutcome.AuthProblem, SyncOutcome.Skipped -> return
                is SyncOutcome.RateLimited -> {
                    val wait = outcome.retryAtMillis?.let { it - now() }
                    if (wait == null || wait > policy.maxRateLimitWaitMs) {
                        stopWithNote(buildId, "GitHub asked the app to slow down. Pull to refresh later to continue.")
                        return
                    }
                    sleep(wait.coerceAtLeast(1_000L))
                }
                is SyncOutcome.TransientError -> {
                    errors++
                    if (errors >= policy.maxConsecutiveErrors) {
                        stopWithNote(buildId, "Lost the connection to GitHub. Pull to refresh to check again.")
                        return
                    }
                    sleep(policy.errorBackoffBaseMs * (1L shl (errors - 1).coerceAtMost(6)))
                }
                is SyncOutcome.Progressed, is SyncOutcome.Unchanged -> {
                    errors = 0
                    sleep(interval)
                    interval = minOf(policy.maxIntervalMs, interval + interval / 2)
                }
            }
        }
    }

    /**
     * ONE status check of ONE build: finds the run if it is not known yet, reads it once and saves the result.
     * Never waits or loops. [maxAgeMs] limits how long after its start a build is still checked.
     */
    suspend fun syncOnce(buildId: String, maxAgeMs: Long): SyncOutcome {
        val record = builds.getBuild(buildId) ?: return SyncOutcome.Skipped
        if (record.isSample || record.status.isTerminal) return SyncOutcome.Skipped
        val (owner, name) = splitRepo(record.repoFullName) ?: return stop(record, "This build has no repository, so it cannot be checked.")
        val age = now() - record.startedAtMillis
        if (age > maxAgeMs) {
            return stop(record, "Stopped checking because this build is too old. Open the run on GitHub to see its result.")
        }
        return try {
            var current = record
            val run: WorkflowRun
            if (record.runId == null) {
                val found = api.findRun(owner, name, CloudBuildConfig.WORKFLOW_FILE, record.branch.orEmpty(), record.id)
                if (found == null) {
                    return if (age > policy.findRunTimeoutMs) {
                        stop(record, "GitHub did not start a run for this build. Check the Actions tab of the repository.")
                    } else {
                        SyncOutcome.Unchanged(record)
                    }
                }
                run = found
                current = record.copy(runId = found.id, runUrl = found.htmlUrl)
                if (record.cancelRequested) {
                    try {
                        api.cancelRun(owner, name, found.id)
                    } catch (e: GitHubException) {
                        // The next check shows whether the run is still going.
                    }
                }
            } else {
                run = api.getRun(owner, name, record.runId)
            }
            val mapped = run.mappedStatus
            if (mapped.isTerminal) return finish(current, run, owner, name)
            val updated = current.copy(
                status = mapped,
                runId = run.id,
                runUrl = run.htmlUrl,
                pollingStopped = false,
                detail = if (current.pollingStopped) null else current.detail,
            )
            if (updated == record) {
                SyncOutcome.Unchanged(record)
            } else {
                builds.saveBuild(updated)
                SyncOutcome.Progressed(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            handleError(record, e)
        }
    }

    private suspend fun finish(record: BuildRecord, run: WorkflowRun, owner: String, name: String): SyncOutcome {
        val finishedAt = now()
        val status = run.mappedStatus
        var detail: String? = null
        var excerpt: String? = null
        var infos = emptyList<com.almus.appcreatorpro.domain.model.BuildArtifactInfo>()
        when (status) {
            BuildStatus.SUCCESS -> infos = api.listArtifacts(owner, name, run.id).toVisibleInfos()
            BuildStatus.FAILED -> {
                detail = "The build failed. Open the log for details."
                try {
                    val jobs = api.listJobs(owner, name, run.id)
                    val failedJob = jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.lastOrNull()
                    val failedStep = jobs.flatMap { it.steps }.firstOrNull { it.conclusion == "failure" }
                    if (failedStep != null) detail = "The build failed at step \"${failedStep.name}\"."
                    if (failedJob != null) {
                        val tail = api.getJobLogTail(owner, name, failedJob.id, 20_000)
                        excerpt = LogExcerpt.extract(tail).ifBlank { null }
                    }
                } catch (e: GitHubException) {
                    // The result itself is already known; the details are a bonus.
                }
            }
            BuildStatus.CANCELLED -> detail = "The build was cancelled."
            else -> Unit
        }
        val finished = record.copy(
            status = status,
            runId = run.id,
            runUrl = run.htmlUrl,
            finishedAtMillis = finishedAt,
            durationSeconds = secondsSince(record.startedAtMillis, finishedAt),
            artifacts = infos,
            artifactNames = infos.map { it.name },
            detail = detail,
            errorExcerpt = excerpt,
            pollingStopped = false,
        )
        builds.saveBuild(finished)
        return SyncOutcome.Finished(finished)
    }

    private suspend fun handleError(record: BuildRecord, e: GitHubException): SyncOutcome = when (e.kind) {
        GitHubException.Kind.RATE_LIMITED -> SyncOutcome.RateLimited(e.retryAtMillis)
        GitHubException.Kind.NETWORK, GitHubException.Kind.SERVER, GitHubException.Kind.TIMEOUT ->
            SyncOutcome.TransientError(e.message ?: "Could not reach GitHub.")
        GitHubException.Kind.TOKEN_REJECTED, GitHubException.Kind.NOT_CONNECTED -> {
            val saved = record.copy(pollingStopped = true, detail = "Your GitHub sign-in is no longer valid. Reconnect GitHub in Settings, then refresh.")
            builds.saveBuild(saved)
            SyncOutcome.AuthProblem(saved)
        }
        else -> stop(record, e.message ?: "GitHub could not report this build.")
    }

    private suspend fun stop(record: BuildRecord, reason: String): SyncOutcome.Stopped {
        val saved = record.copy(pollingStopped = true, detail = reason)
        builds.saveBuild(saved)
        return SyncOutcome.Stopped(saved, reason)
    }

    private suspend fun stopWithNote(buildId: String, note: String) {
        val record = builds.getBuild(buildId) ?: return
        if (record.status.isTerminal) return
        builds.saveBuild(record.copy(pollingStopped = true, detail = note))
    }

    // ------------------------------------------------------------------ helpers

    private class Remote(
        val repo: GitHubRepo?,
        val branch: GitHubBranch?,
        val commit: CommitSummary?,
        val workflow: WorkflowCheck?,
        val blockers: List<String>,
    )

    private fun localBlockers(validation: ValidationReport, config: CloudBuildConfig, buildConfig: BuildConfiguration): List<String> {
        val blockers = ArrayList<String>(validation.errors)
        blockers += BuildConfigurationValidator.validate(buildConfig)
        if (buildConfig.needsReleaseSigning) {
            val reasons = signing?.blockers(buildConfig, config.repoFullName)
                ?: listOf("Release signing is not set up on this phone.")
            blockers += reasons
        }
        return blockers
    }

    private suspend fun inspectRemote(config: CloudBuildConfig, buildConfig: BuildConfiguration): Remote {
        var repo: GitHubRepo? = null
        var branch: GitHubBranch? = null
        var commit: CommitSummary? = null
        var workflow: WorkflowCheck? = null
        val blockers = ArrayList<String>()
        try {
            repo = api.getRepository(config.repoOwner, config.repoName)
            if (!repo.canPush) blockers += "You do not have write access to ${repo.fullName}."
            branch = api.getBranch(config.repoOwner, config.repoName, config.branch)
            commit = api.getLatestCommit(config.repoOwner, config.repoName, config.branch)
            val check = checkWorkflow(config, repo.defaultBranch)
            workflow = check
            workflowBlocker(check, config, buildConfig)?.let { blockers += it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            blockers += e.message ?: "GitHub could not be reached."
        }
        return Remote(repo, branch, commit, workflow, blockers)
    }

    private suspend fun checkWorkflow(config: CloudBuildConfig, defaultBranch: String): WorkflowCheck {
        val onBranch = api.getFileText(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_PATH, config.branch)
            ?: return WorkflowCheck.MISSING
        val version = CloudBuildConfig.workflowVersionOf(onBranch)
        if (version == null || version < CloudBuildConfig.MIN_WORKFLOW_VERSION) return WorkflowCheck.OUTDATED
        if (config.branch != defaultBranch &&
            api.getFileText(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_PATH, defaultBranch) == null
        ) {
            return WorkflowCheck.NOT_ON_DEFAULT_BRANCH
        }
        return if (version < CloudBuildConfig.WORKFLOW_VERSION) WorkflowCheck.LEGACY else WorkflowCheck.OK
    }

    private fun workflowBlocker(check: WorkflowCheck, config: CloudBuildConfig, buildConfig: BuildConfiguration): String? = when (check) {
        WorkflowCheck.OK -> null
        WorkflowCheck.MISSING ->
            "The workflow file is missing on branch \"${config.branch}\". Use \"Show workflow\" and add it as ${CloudBuildConfig.WORKFLOW_PATH}."
        WorkflowCheck.OUTDATED ->
            "The workflow file in the repository is too old. Replace it with the current one (\"Show workflow\")."
        WorkflowCheck.NOT_ON_DEFAULT_BRANCH ->
            "GitHub only starts a workflow that also exists on the default branch. Add the workflow file there too."
        WorkflowCheck.LEGACY ->
            if (needsWorkflowV2(buildConfig)) {
                "The workflow file in the repository is an older version that can only build a debug APK. Replace it with the current one to use these settings."
            } else {
                null
            }
    }

    private fun needsWorkflowV2(c: BuildConfiguration): Boolean =
        c.gradleTask != "assembleDebug" || c.needsReleaseSigning ||
            !c.applicationId.isNullOrBlank() || !c.versionName.isNullOrBlank() ||
            c.versionCode != null || c.minSdk != null || c.targetSdk != null ||
            c.options.minify != null || c.options.shrinkResources != null

    private suspend fun readFiles(project: Project): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (entry in files.listFiles(project.id)) {
            try {
                out[entry.path] = files.readFile(project.id, entry.path)
            } catch (e: StorageException) {
                // A file that cannot be read as text is left out; the validator reports a project without usable files.
            }
        }
        return out
    }

    private fun splitRepo(fullName: String?): Pair<String, String>? {
        val parts = fullName?.split('/') ?: return null
        return if (parts.size == 2 && parts.all { it.isNotBlank() }) parts[0] to parts[1] else null
    }

    private fun secondsSince(startMillis: Long, endMillis: Long): Int = ((endMillis - startMillis) / 1000L).toInt().coerceAtLeast(0)
}
