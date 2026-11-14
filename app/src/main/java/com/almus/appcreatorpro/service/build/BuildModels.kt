package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.ValidationReport
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord

sealed interface StartResult {
    /** Nothing was uploaded; [reasons] explain why. */
    data class Rejected(val reasons: List<String>) : StartResult

    data class Started(val record: BuildRecord) : StartResult
}

/** State of almus-cloud-build.yml in the repository. */
enum class WorkflowCheck {
    OK,
    MISSING,
    OUTDATED,
    NOT_ON_DEFAULT_BRANCH,

    /** Version 1: still builds a plain debug APK, but not release/AAB builds or overrides. */
    LEGACY,
}

/** Result of the read-only check before a build. [blockers] empty = ready. */
data class PreflightResult(
    val validation: ValidationReport,
    val repo: GitHubRepo?,
    val branch: GitHubBranch?,
    val latestCommit: CommitSummary?,
    val workflow: WorkflowCheck?,
    val blockers: List<String>,
) {
    val canBuild: Boolean get() = blockers.isEmpty()
}

data class FailedStep(val jobName: String, val stepName: String)

/** The end of a build log, cleaned of colours and secrets, plus the compiler/Gradle errors found in it. */
data class BuildLogView(
    val text: String?,
    val errors: List<String>,
    val note: String?,
)

data class BuildDetails(
    val jobs: List<WorkflowJob>,
    val jobsNote: String?,
    val failedSteps: List<FailedStep>,
    val artifacts: List<BuildArtifactInfo>,
    val artifactsNote: String?,
    val log: BuildLogView?,
)

sealed interface DetailsResult {
    data class Loaded(val details: BuildDetails) : DetailsResult
    data class Failed(val message: String, val retryable: Boolean) : DetailsResult
}
