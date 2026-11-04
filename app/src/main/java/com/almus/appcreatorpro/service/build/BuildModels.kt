package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.ValidationReport
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord

/** State of the almus-cloud-build.yml workflow file in the chosen repository. */
enum class WorkflowCheck {
    /** Present with the current version on the build branch and on the default branch. */
    OK,

    /** Not found on the build branch. */
    MISSING,

    /** Found, but without the current version marker. */
    OUTDATED,

    /** On the build branch only: GitHub also needs it on the default branch. */
    NOT_ON_DEFAULT_BRANCH,
}

/** Result of the read-only check made before a build. Nothing is uploaded to produce it. */
data class PreflightResult(
    val validation: ValidationReport,
    val repo: GitHubRepo? = null,
    val branch: GitHubBranch? = null,
    val latestCommit: CommitSummary? = null,
    val workflow: WorkflowCheck? = null,
    /** Everything that stops a build (project problems and repository problems). User-safe text. */
    val blockers: List<String> = emptyList(),
) {
    val canBuild: Boolean get() = blockers.isEmpty()
}

sealed interface StartResult {
    /** Nothing was uploaded and no history entry was created. [reasons] are user-safe. */
    data class Rejected(val reasons: List<String>) : StartResult

    /** The project was uploaded and the workflow was started. */
    data class Started(val record: BuildRecord) : StartResult
}

/** A step that GitHub reported as failed. */
data class FailedStep(val jobName: String, val stepName: String)

/**
 * The end of a build log, cleaned of colours, timestamps and secrets. [errors] are the recognised problems,
 * [note] explains why a log is missing or incomplete.
 */
data class BuildLogView(
    val text: String?,
    val errors: List<String>,
    val note: String?,
)

/** Steps, artifacts and (for failed builds) the log of one build, as last read from GitHub. */
data class BuildDetails(
    val jobs: List<WorkflowJob>,
    /** Set when the steps could not be loaded. */
    val jobsNote: String?,
    val failedSteps: List<FailedStep>,
    val artifacts: List<BuildArtifactInfo>,
    /** Set when the artifacts could not be checked (or no longer exist). */
    val artifactsNote: String?,
    val log: BuildLogView?,
)

sealed interface DetailsResult {
    data class Loaded(val details: BuildDetails) : DetailsResult
    data class Failed(val message: String, val retryable: Boolean) : DetailsResult
}

/** Splits "owner/name". Returns null when the text is not in that form. */
internal fun splitRepo(fullName: String?): Pair<String, String>? {
    val text = fullName.orEmpty()
    val owner = text.substringBefore('/')
    val name = text.substringAfter('/', "")
    return if (owner.isEmpty() || name.isEmpty()) null else owner to name
}

internal object BuildDetailsSupport {
    /** How much of the end of a job log is requested from GitHub. */
    const val LOG_TAIL_CHARS = 60_000

    fun failedSteps(jobs: List<WorkflowJob>): List<FailedStep> =
        jobs.flatMap { job -> job.steps.filter { it.conclusion == "failure" }.map { FailedStep(job.name, it.name) } }

    /** The job whose log is most useful: the failed one, otherwise the last one. */
    fun pickLogJob(jobs: List<WorkflowJob>): WorkflowJob? =
        jobs.firstOrNull { it.conclusion == "failure" } ?: jobs.lastOrNull()
}

/** The result of ONE status check of one build ([CloudBuildOrchestrator.syncOnce]). Nothing here is ever a secret. */
sealed interface SyncOutcome {
    /** Nothing to check: the build is missing, a sample, or already finished. */
    data object Skipped : SyncOutcome

    /** GitHub was asked and nothing changed. */
    data object Unchanged : SyncOutcome

    /** The record changed but the build is still going. */
    data class Progressed(val record: BuildRecord) : SyncOutcome

    /** The build reached a final state; [record] is the saved final record. */
    data class Finished(val record: BuildRecord) : SyncOutcome

    /** Checking was given up on (too old, run unknown to GitHub, no run appeared). The reason is on the record. */
    data class Stopped(val record: BuildRecord) : SyncOutcome

    /** The GitHub sign-in is no longer valid. Checking is stopped until the user reconnects. */
    data class AuthProblem(val record: BuildRecord) : SyncOutcome

    /** GitHub's rate limit was hit; nothing was changed. [retryAtMillis] is when it resets, if GitHub said so. */
    data class RateLimited(val retryAtMillis: Long?) : SyncOutcome

    /** Network or server trouble; nothing was changed, so the check can simply be repeated. */
    data class TransientError(val message: String) : SyncOutcome
}
