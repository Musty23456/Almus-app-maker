package com.almus.appcreatorpro.service.build

import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubBranch
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.github.ValidationReport
import com.almus.appcreatorpro.domain.github.WorkflowJob
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord

/** What the app found when it looked at the workflow file in the repository. */
enum class WorkflowCheck {
    OK,
    MISSING,
    OUTDATED,
    NOT_ON_DEFAULT_BRANCH,

    /** Workflow version 1: builds a debug APK only. */
    LEGACY,
}

/** The result of the read-only check before a build. [blockers] are plain-words reasons why it cannot start. */
data class PreflightResult(
    val validation: ValidationReport,
    val repo: GitHubRepo? = null,
    val branch: GitHubBranch? = null,
    val latestCommit: CommitSummary? = null,
    val workflow: WorkflowCheck? = null,
    val blockers: List<String> = emptyList(),
) {
    val canBuild: Boolean get() = blockers.isEmpty()
}

sealed interface StartResult {
    /** The build was prepared, uploaded and started on GitHub. */
    data class Started(val record: BuildRecord) : StartResult

    /** Nothing was uploaded. [reasons] explain why. */
    data class Rejected(val reasons: List<String>) : StartResult
}

data class FailedStep(val jobName: String, val stepName: String)

/** A cleaned excerpt of a build log. [text] is null when there is no log; [note] explains why or what was cut. */
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
    val log: BuildLogView?,
    val artifactsNote: String? = null,
)

sealed interface DetailsResult {
    data class Loaded(val details: BuildDetails) : DetailsResult
    data class Failed(val message: String, val retryable: Boolean) : DetailsResult
}
