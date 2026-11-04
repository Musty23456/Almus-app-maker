package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildConfigurationValidator
import com.almus.appcreatorpro.domain.build.SigningReadiness
import com.almus.appcreatorpro.domain.build.WorkflowInputs
import com.almus.appcreatorpro.domain.error.toUserMessage
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
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.repository.BuildStore
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

/** 12 lowercase hex characters: accepted by the workflow's build_id check and safe as a file name. */
internal fun randomBuildId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

/**
 * Runs one cloud build: checks everything first ([preflight], read-only), uploads the project and starts the
 * workflow ([start]), then follows the GitHub Actions run until it finishes ([follow]).
 * Time and sleeping are injected so tests run instantly.
 */
class CloudBuildOrchestrator(
    private val api: GitHubApi,
    private val files: SourceFileRepository,
    private val builds: BuildStore,
    private val policy: PollingPolicy = PollingPolicy(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val newBuildId: () -> String = { randomBuildId() },
    /** Phase 6: tells whether release signing is ready. Null means no release build can be started. */
    private val signing: SigningReadiness? = null,
) {

    private class Inspection(val result: PreflightResult, val files: Map<String, String>, val workflowVersion: Int)

    private sealed interface Step {
        data object Continue : Step
        data class Stop(val detail: String) : Step
    }

    // ------------------------------------------------------------------------------------------ preflight

    /** Checks the project, repository, branch and workflow. Uploads and changes nothing. */
    suspend fun preflight(
        project: Project,
        config: CloudBuildConfig,
        buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT,
    ): PreflightResult = inspect(project, config, buildConfig).result

    private suspend fun inspect(project: Project, config: CloudBuildConfig, buildConfig: BuildConfiguration): Inspection {
        val blockers = ArrayList<String>()

        var projectFiles: Map<String, String> = emptyMap()
        var readFailed = false
        try {
            projectFiles = readProject(project.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            readFailed = true
            blockers += "The project files could not be read. ${e.toUserMessage()}"
        }
        val validation = if (readFailed) {
            ValidationReport(emptyList(), emptyList(), 0, 0L)
        } else {
            CloudBuildValidator.validate(project.type, projectFiles)
        }
        blockers += validation.errors

        CloudBuildInputValidator.validateDirectory(config.projectDir)?.let { blockers += it }
        CloudBuildInputValidator.validateGradleVersion(config.gradleVersion)?.let { blockers += it }
        CloudBuildInputValidator.validateBranchName(config.branch)?.let { blockers += it }
        blockers += BuildConfigurationValidator.validate(buildConfig)

        var repo: GitHubRepo? = null
        var branch: GitHubBranch? = null
        var latestCommit: CommitSummary? = null
        var workflow: WorkflowCheck? = null
        var workflowVersion = CloudBuildConfig.WORKFLOW_VERSION

        if (config.repoOwner.isBlank() || config.repoName.isBlank()) {
            blockers += "Choose a GitHub repository for this project first."
        } else {
            try {
                val foundRepo = api.getRepository(config.repoOwner, config.repoName)
                repo = foundRepo
                if (!foundRepo.canPush) {
                    blockers += "You only have read access to ${foundRepo.fullName}, so the project cannot be uploaded there. Choose a repository you can write to."
                }
                val foundBranch = api.getBranch(config.repoOwner, config.repoName, config.branch)
                branch = foundBranch
                latestCommit = api.getLatestCommit(config.repoOwner, config.repoName, config.branch)

                val found = checkWorkflow(config, foundRepo)
                val check = found.check
                workflow = check
                workflowVersion = found.version
                when (check) {
                    WorkflowCheck.OK -> Unit
                    WorkflowCheck.LEGACY -> {
                        if (buildConfig.needsAdvancedWorkflow) {
                            blockers += "This build type or these settings need the newer workflow file (version 2), but this repository has " +
                                "version ${found.version}. Copy the current workflow from the app, commit it to \"${config.branch}\" and to the " +
                                "default branch, then try again."
                        }
                    }
                    WorkflowCheck.MISSING -> blockers += "The workflow file ${CloudBuildConfig.WORKFLOW_PATH} is missing on branch \"${config.branch}\". Use \"Copy workflow\" and \"Open GitHub\" in the Workflow setup section to add it."
                    WorkflowCheck.OUTDATED -> blockers += "The workflow file ${CloudBuildConfig.WORKFLOW_PATH} is out of date. Copy the current version from the app and commit it again."
                    WorkflowCheck.NOT_ON_DEFAULT_BRANCH -> blockers += "The workflow file ${CloudBuildConfig.WORKFLOW_PATH} must also exist on the default branch \"${foundRepo.defaultBranch}\". Add it there as well."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException) {
                blockers += e.message.orEmpty().ifBlank { "GitHub could not be reached. Check your connection and try again." }
            }
        }

        if (buildConfig.needsReleaseSigning) {
            val readiness = signing
            if (readiness == null) {
                blockers += "Release signing is not available in this version of the app."
            } else if (config.repoOwner.isNotBlank() && config.repoName.isNotBlank()) {
                blockers += readiness.blockers(buildConfig, config.repoFullName)
            }
        }

        return Inspection(
            PreflightResult(
                validation = validation,
                repo = repo,
                branch = branch,
                latestCommit = latestCommit,
                workflow = workflow,
                blockers = blockers.distinct(),
            ),
            projectFiles,
            workflowVersion,
        )
    }

    private class WorkflowFacts(val check: WorkflowCheck, val version: Int)

    private suspend fun checkWorkflow(config: CloudBuildConfig, repo: GitHubRepo): WorkflowFacts {
        val onBranch = api.getFileText(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_PATH, config.branch)
        val onDefault = if (config.branch == repo.defaultBranch) {
            onBranch
        } else {
            api.getFileText(config.repoOwner, config.repoName, CloudBuildConfig.WORKFLOW_PATH, repo.defaultBranch)
        }
        if (onBranch == null) return WorkflowFacts(WorkflowCheck.MISSING, 0)
        if (onDefault == null) return WorkflowFacts(WorkflowCheck.NOT_ON_DEFAULT_BRANCH, 0)
        val branchVersion = CloudBuildConfig.workflowVersionOf(onBranch)
        val defaultVersion = CloudBuildConfig.workflowVersionOf(onDefault)
        // Version 1 still builds plain debug APKs, so it is accepted here; builds that need version 2 are refused by the caller.
        if (branchVersion == null || defaultVersion == null ||
            branchVersion < CloudBuildConfig.MIN_WORKFLOW_VERSION || defaultVersion < CloudBuildConfig.MIN_WORKFLOW_VERSION
        ) {
            return WorkflowFacts(WorkflowCheck.OUTDATED, 0)
        }
        val version = minOf(branchVersion, defaultVersion)
        return WorkflowFacts(if (version < CloudBuildConfig.WORKFLOW_VERSION) WorkflowCheck.LEGACY else WorkflowCheck.OK, version)
    }

    private suspend fun readProject(projectId: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (entry in files.listFiles(projectId).sortedBy { it.path }) {
            result[entry.path] = files.readFile(projectId, entry.path)
        }
        return result
    }

    // ------------------------------------------------------------------------------------------ start

    /**
     * Uploads the project in one commit and starts the workflow. Returns [StartResult.Rejected] (nothing uploaded,
     * no history entry) when the preflight finds a blocker. If GitHub refuses the upload or the start, the history
     * entry is marked failed and the [GitHubException] is rethrown.
     */
    suspend fun start(
        project: Project,
        config: CloudBuildConfig,
        buildConfig: BuildConfiguration = BuildConfiguration.DEFAULT,
    ): StartResult {
        val inspection = inspect(project, config, buildConfig)
        if (!inspection.result.canBuild) return StartResult.Rejected(inspection.result.blockers)

        val id = newBuildId()
        var record = BuildRecord(
            id = id,
            projectName = project.name,
            status = BuildStatus.PREPARING,
            startedAtMillis = now(),
            durationSeconds = null,
            summary = "${config.repoFullName} (${config.branch})",
            isSample = false,
            projectId = project.id,
            repoFullName = config.repoFullName,
            branch = config.branch,
            projectDir = config.projectDir,
            detail = "Uploading the project files to GitHub...",
            configuration = buildConfig,
        )
        builds.saveBuild(record)

        try {
            val commit = api.commitDirectory(
                owner = config.repoOwner,
                name = config.repoName,
                branch = config.branch,
                message = "Almus: upload ${project.name} (build $id)",
                directory = config.projectDir,
                files = inspection.files,
            )
            record = record.copy(commitSha = commit.commitSha, detail = "Starting the build on GitHub...")
            builds.saveBuild(record)

            val runId = api.dispatchWorkflow(
                owner = config.repoOwner,
                name = config.repoName,
                workflowFile = CloudBuildConfig.WORKFLOW_FILE,
                ref = config.branch,
                inputs = WorkflowInputs.build(config.projectDir, id, config.gradleVersion, buildConfig, inspection.workflowVersion),
            )
            record = record.copy(
                status = if (runId != null) BuildStatus.QUEUED else BuildStatus.PREPARING,
                runId = runId,
                runUrl = runId?.let { "https://github.com/${config.repoFullName}/actions/runs/$it" },
                detail = "Waiting for GitHub to start the run...",
            )
            builds.saveBuild(record)
            return StartResult.Started(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markStartFailed(record, e)
            throw e
        }
    }

    private suspend fun markStartFailed(record: BuildRecord, cause: Exception) {
        val finishedAt = now()
        val detail = if (cause is GitHubException) {
            "The build could not be started: ${cause.message.orEmpty()}"
        } else {
            "The build could not be started."
        }
        try {
            builds.saveBuild(
                record.copy(
                    status = BuildStatus.FAILED,
                    detail = detail,
                    finishedAtMillis = finishedAt,
                    durationSeconds = secondsSince(record.startedAtMillis, finishedAt),
                ),
            )
        } catch (c: CancellationException) {
            throw c
        } catch (ignored: Exception) {
            // The original problem is what the user needs to see.
        }
    }

    // ------------------------------------------------------------------------------------------ follow

    /**
     * Follows the GitHub run of [buildId] until it finishes or checking has to stop (timeout, lost connection,
     * expired sign-in, rate limit). The reason is saved on the record. Finished builds and sample builds are ignored.
     */
    suspend fun follow(buildId: String) {
        val record = builds.getBuild(buildId) ?: return
        if (record.isSample || record.status.isTerminal) return
        val coordinates = splitRepo(record.repoFullName)
        if (coordinates == null) {
            stop(record, "This build has no repository information, so its status cannot be checked.")
            return
        }
        val (owner, repo) = coordinates
        val pollStart = now()

        val located = if (record.runId != null) record else locateRun(record, owner, repo, pollStart) ?: return
        val runId = located.runId ?: return
        poll(located, owner, repo, runId, pollStart)
    }

    /** Waits for GitHub to create the run. Returns the record with the run id, or null after saving why it gave up. */
    private suspend fun locateRun(start: BuildRecord, owner: String, repo: String, pollStart: Long): BuildRecord? {
        var record = start
        val deadline = pollStart + policy.findRunTimeoutMs
        var errors = 0
        while (true) {
            try {
                val run = api.findRun(owner, repo, CloudBuildConfig.WORKFLOW_FILE, record.branch.orEmpty(), record.id)
                errors = 0
                if (run != null) {
                    val mapped = run.mappedStatus
                    record = record.copy(
                        runId = run.id,
                        runUrl = run.htmlUrl,
                        status = if (mapped.isTerminal) BuildStatus.RUNNING else mapped,
                        detail = "GitHub started the run.",
                        pollingStopped = false,
                    )
                    builds.saveBuild(record)
                    sendPendingCancel(record, owner, repo, run.id)
                    return record
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException) {
                errors++
                val step = handle(e, errors)
                if (step is Step.Stop) {
                    stop(record, step.detail)
                    return null
                }
            }
            if (now() >= deadline) {
                stop(
                    record,
                    "GitHub did not start a run for this build within ${policy.findRunTimeoutMs / 1000} seconds. " +
                        "Check the Actions tab of the repository, then tap refresh.",
                )
                return null
            }
            sleep(policy.findRunIntervalMs)
        }
    }

    private suspend fun poll(start: BuildRecord, owner: String, repo: String, runId: Long, pollStart: Long) {
        var record = start
        var interval = policy.initialIntervalMs
        var errors = 0
        while (true) {
            if (now() - pollStart >= policy.overallTimeoutMs) {
                stop(
                    record,
                    "Stopped checking after ${policy.overallTimeoutMs / 60_000} minutes. " +
                        "The build may still be running on GitHub. Tap refresh to check again.",
                )
                return
            }
            try {
                val run = api.getRun(owner, repo, runId)
                errors = 0
                val status = run.mappedStatus
                if (status.isTerminal) {
                    finish(record, run, owner, repo)
                    return
                }
                val detail = if (status == BuildStatus.RUNNING) {
                    "Building on GitHub..."
                } else {
                    "Waiting for a GitHub runner to pick up the build..."
                }
                if (record.status != status || record.detail != detail || record.runUrl != run.htmlUrl || record.pollingStopped) {
                    record = record.copy(status = status, detail = detail, runUrl = run.htmlUrl, pollingStopped = false)
                    builds.saveBuild(record)
                }
                sleep(interval)
                interval = (interval + interval / 2).coerceAtMost(policy.maxIntervalMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: GitHubException) {
                errors++
                val step = handle(e, errors)
                if (step is Step.Stop) {
                    stop(record, step.detail)
                    return
                }
            }
        }
    }

    /** Decides what to do about a GitHub error while following a run. May sleep before returning [Step.Continue]. */
    private suspend fun handle(e: GitHubException, consecutiveErrors: Int): Step = when {
        e.isAuthProblem ->
            Step.Stop("Your GitHub sign-in is no longer valid. Reconnect GitHub, then open this build again to continue checking it.")

        e.kind == GitHubException.Kind.RATE_LIMITED -> {
            val wait = e.retryAtMillis?.let { it - now() }
            if (wait != null && wait > policy.maxRateLimitWaitMs) {
                Step.Stop("GitHub's rate limit has been reached. Open this build again in about ${wait / 60_000 + 1} minutes.")
            } else {
                sleep((wait ?: policy.errorBackoffBaseMs).coerceIn(1_000L, policy.maxRateLimitWaitMs))
                Step.Continue
            }
        }

        e.isTransient ->
            if (consecutiveErrors >= policy.maxConsecutiveErrors) {
                Step.Stop("Lost the connection to GitHub. Open this build again to resume checking it.")
            } else {
                sleep(policy.errorBackoffBaseMs * consecutiveErrors)
                Step.Continue
            }

        else -> Step.Stop(e.message.orEmpty().ifBlank { "GitHub reported a problem, so checking stopped." })
    }

    private suspend fun finish(record: BuildRecord, run: WorkflowRun, owner: String, repo: String): BuildRecord {
        val status = run.mappedStatus
        var artifacts: List<BuildArtifactInfo> = emptyList()
        var excerpt: String? = null
        var detail: String

        when (status) {
            BuildStatus.SUCCESS -> {
                var note = ""
                try {
                    artifacts = api.listArtifacts(owner, repo, run.id).toVisibleInfos()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubException) {
                    note = " The artifact list could not be loaded yet; open the build to check again."
                }
                val summary = when {
                    artifacts.any { it.isApk } -> "Build finished. The APK is ready to download."
                    artifacts.any { it.isBundle } -> "Build finished. The AAB is ready to download."
                    else -> "Build finished, but GitHub lists no APK or AAB for this run."
                }
                detail = summary + note
            }

            BuildStatus.CANCELLED -> detail = "The build was cancelled on GitHub."

            else -> {
                detail = "The build failed on GitHub."
                try {
                    val jobs = api.listJobs(owner, repo, run.id)
                    val failed = BuildDetailsSupport.failedSteps(jobs)
                    if (failed.isNotEmpty()) detail = "The build failed at step \"${failed.first().stepName}\"."
                    val logJob = BuildDetailsSupport.pickLogJob(jobs)
                    if (logJob != null) {
                        val raw = api.getJobLogTail(owner, repo, logJob.id, BuildDetailsSupport.LOG_TAIL_CHARS)
                        excerpt = LogExcerpt.extract(raw).ifBlank { null }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubException) {
                    // The status is already known; the log can be loaded later from the build screen.
                }
            }
        }

        val finishedAt = now()
        val finished = record.copy(
            status = status,
            runUrl = run.htmlUrl,
            commitSha = record.commitSha ?: run.headSha,
            detail = detail,
            errorExcerpt = excerpt,
            artifacts = artifacts,
            artifactNames = artifacts.filter { it.status == ArtifactStatus.AVAILABLE }.map { it.name },
            finishedAtMillis = finishedAt,
            durationSeconds = secondsSince(record.startedAtMillis, finishedAt),
            pollingStopped = false,
        )
        builds.saveBuild(finished)
        return finished
    }

    private suspend fun stop(record: BuildRecord, detail: String): BuildRecord {
        val stopped = record.copy(pollingStopped = true, detail = detail)
        builds.saveBuild(stopped)
        return stopped
    }

    // ------------------------------------------------------------------------------------------ cancel (Phase 6)

    /**
     * Asks GitHub to cancel the run of [record]. GitHub answers "accepted" and then stops the run, so the build keeps
     * being followed to record the real end. If the run does not exist yet, the wish is remembered on the record and
     * sent as soon as the run is found. A [GitHubException] from GitHub is rethrown (the caller shows its message).
     */
    suspend fun cancel(record: BuildRecord): String {
        val current = builds.getBuild(record.id) ?: record
        if (current.status.isTerminal) return "This build has already finished."
        val coordinates = splitRepo(current.repoFullName)
        val runId = current.runId
        if (coordinates == null || runId == null) {
            builds.saveBuild(
                current.copy(
                    cancelRequested = true,
                    detail = "Cancel requested. It is sent as soon as GitHub has created the run.",
                ),
            )
            return "Cancel requested. It is sent as soon as GitHub has created the run."
        }
        api.cancelRun(coordinates.first, coordinates.second, runId)
        builds.saveBuild(current.copy(cancelRequested = true, detail = "Cancel sent to GitHub. Waiting for the run to stop..."))
        return "Cancel sent to GitHub."
    }

    /** A cancel asked for before the run existed is sent once, right after the run is found. Failures are not fatal. */
    private suspend fun sendPendingCancel(record: BuildRecord, owner: String, repo: String, runId: Long) {
        if (!record.cancelRequested) return
        try {
            api.cancelRun(owner, repo, runId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            // The user can press Cancel again; the run is followed either way.
        }
    }

    // ------------------------------------------------------------------------------------------ sync once (Phase 7)

    /**
     * ONE status check of [buildId], for background monitoring. It never sleeps and never loops: it asks GitHub at most
     * once for the run (or looks it up when the run id is not known yet) and saves what it learns. Problems that can
     * simply be repeated (network, server, rate limit) change nothing. A build older than [maxAgeMillis] is marked
     * stopped without asking GitHub.
     */
    suspend fun syncOnce(buildId: String, maxAgeMillis: Long): SyncOutcome {
        val record = builds.getBuild(buildId) ?: return SyncOutcome.Skipped
        if (record.isSample || record.status.isTerminal) return SyncOutcome.Skipped
        val coordinates = splitRepo(record.repoFullName)
            ?: return SyncOutcome.Stopped(stop(record, "This build has no repository information, so its status cannot be checked."))
        val (owner, repo) = coordinates
        val nowMillis = now()
        val age = nowMillis - record.startedAtMillis

        if (age > maxAgeMillis) {
            val hours = (maxAgeMillis / 3_600_000L).coerceAtLeast(1L)
            return SyncOutcome.Stopped(
                stop(
                    record,
                    "Stopped checking after $hours hours. The build may still be running on GitHub. Open the build and tap refresh to check again.",
                ),
            )
        }

        try {
            val runId = record.runId
            if (runId == null) {
                val run = api.findRun(owner, repo, CloudBuildConfig.WORKFLOW_FILE, record.branch.orEmpty(), record.id)
                if (run == null) {
                    return if (age >= policy.findRunTimeoutMs) {
                        SyncOutcome.Stopped(
                            stop(
                                record,
                                "GitHub did not start a run for this build within ${policy.findRunTimeoutMs / 1000} seconds. " +
                                    "Check the Actions tab of the repository, then tap refresh.",
                            ),
                        )
                    } else {
                        SyncOutcome.Unchanged
                    }
                }
                val mapped = run.mappedStatus
                val located = record.copy(
                    runId = run.id,
                    runUrl = run.htmlUrl,
                    status = if (mapped.isTerminal) BuildStatus.RUNNING else mapped,
                    detail = "GitHub started the run.",
                    pollingStopped = false,
                )
                builds.saveBuild(located)
                sendPendingCancel(located, owner, repo, run.id)
                return SyncOutcome.Progressed(located)
            }

            val run = api.getRun(owner, repo, runId)
            val status = run.mappedStatus
            if (status.isTerminal) return SyncOutcome.Finished(finish(record, run, owner, repo))
            val detail = if (status == BuildStatus.RUNNING) {
                "Building on GitHub..."
            } else {
                "Waiting for a GitHub runner to pick up the build..."
            }
            if (record.status == status && record.detail == detail && record.runUrl == run.htmlUrl && !record.pollingStopped) {
                return SyncOutcome.Unchanged
            }
            val updated = record.copy(status = status, detail = detail, runUrl = run.htmlUrl, pollingStopped = false)
            builds.saveBuild(updated)
            return SyncOutcome.Progressed(updated)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubException) {
            return when {
                e.isAuthProblem -> SyncOutcome.AuthProblem(
                    stop(record, "Your GitHub sign-in is no longer valid. Reconnect GitHub, then open this build again to continue checking it."),
                )
                e.kind == GitHubException.Kind.RATE_LIMITED -> SyncOutcome.RateLimited(e.retryAtMillis)
                e.isTransient -> SyncOutcome.TransientError(e.message.orEmpty())
                else -> SyncOutcome.Stopped(stop(record, e.message.orEmpty().ifBlank { "GitHub reported a problem, so checking stopped." }))
            }
        }
    }

    private fun secondsSince(startMillis: Long, endMillis: Long): Int =
        ((endMillis - startMillis) / 1000L).coerceAtLeast(0L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
