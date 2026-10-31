package com.almus.appcreatorpro.domain.github

import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildStatus

/**
 * How the user signs in to GitHub. All methods use the OAuth *device flow*, which needs only a public
 * client id (no client secret), so nothing secret is ever shipped inside the APK.
 */
enum class AuthMethod(
    val id: String,
    val label: String,
    /** OAuth scopes requested at sign-in. Empty for GitHub Apps (their permissions are set on the app itself). */
    val requestedScopes: List<String>,
    val summary: String,
) {
    GITHUB_APP(
        id = "github_app",
        label = "GitHub App (recommended)",
        requestedScopes = emptyList(),
        summary = "Least privilege: access is limited to the repositories you install your own GitHub App on, " +
            "with only Contents, Actions and Metadata permissions. Cannot create repositories.",
    ),
    OAUTH_PUBLIC(
        id = "oauth_public",
        label = "OAuth App - public repositories",
        requestedScopes = listOf("public_repo"),
        summary = "Simpler setup. Scope public_repo: read and write access to ALL your public repositories. " +
            "Cannot see private repositories.",
    ),
    OAUTH_PRIVATE(
        id = "oauth_private",
        label = "OAuth App - private + public",
        requestedScopes = listOf("repo"),
        summary = "Simpler setup, but the broadest option. Scope repo: read and write access to ALL your " +
            "repositories, including private ones.",
    ),
    ;

    val isOAuthApp: Boolean get() = this != GITHUB_APP

    companion object {
        fun fromId(id: String?): AuthMethod? = entries.firstOrNull { it.id == id }
    }
}

data class GitHubUser(val login: String, val name: String? = null)

/** What the signed-in token is actually allowed to do, derived from what GitHub granted (not what we asked for). */
data class GrantedAccess(
    val method: AuthMethod,
    val scopes: Set<String>,
) {
    /** Repositories can only be created with an OAuth token that carries a repo scope. */
    val canCreatePublicRepository: Boolean
        get() = method.isOAuthApp && ("public_repo" in scopes || "repo" in scopes)

    val canCreatePrivateRepository: Boolean
        get() = method.isOAuthApp && "repo" in scopes

    val canCreateRepository: Boolean get() = canCreatePublicRepository

    fun describe(): String = when {
        method == AuthMethod.GITHUB_APP -> "GitHub App: only repositories where your app is installed"
        scopes.isEmpty() -> "OAuth App: no scopes granted"
        else -> "OAuth App scopes: ${scopes.sorted().joinToString(", ")}"
    }

    companion object {
        fun parseScopes(raw: String?): Set<String> =
            raw.orEmpty().split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}

data class GitHubRepo(
    val id: Long,
    val owner: String,
    val name: String,
    val isPrivate: Boolean,
    val defaultBranch: String,
    /** False when the user only has read access: uploading would fail, so the UI disables selection. */
    val canPush: Boolean,
    val htmlUrl: String,
    val description: String? = null,
) {
    val fullName: String get() = "$owner/$name"
}

data class RepoPage(val items: List<GitHubRepo>, val nextPage: Int?)

data class GitHubBranch(val name: String, val headSha: String, val isProtected: Boolean = false)

data class CommitSummary(
    val sha: String,
    val message: String,
    val author: String?,
    val dateIso: String?,
) {
    val shortSha: String get() = sha.take(7)
    val title: String get() = message.lineSequence().firstOrNull().orEmpty()
}

/** Result of committing a project directory. [changed] is false when the repository already had these exact files. */
data class CommitResult(val commitSha: String, val changed: Boolean, val deletionsSkipped: Boolean = false)

/** The slice of a GitHub Actions workflow run the app needs. Raw GitHub values are kept for diagnostics. */
data class WorkflowRun(
    val id: Long,
    val displayTitle: String,
    val status: String,
    val conclusion: String?,
    val htmlUrl: String,
    val headSha: String?,
    val createdAtIso: String?,
) {
    val mappedStatus: BuildStatus get() = WorkflowStatusMapper.map(status, conclusion)
}

data class WorkflowArtifact(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
    /** Epoch millis when GitHub will delete the artifact, if it said so. */
    val expiresAtMillis: Long? = null,
)

fun WorkflowArtifact.toInfo() = BuildArtifactInfo(
    id = id,
    name = name,
    sizeBytes = sizeBytes,
    expiresAtMillis = expiresAtMillis,
    status = if (expired) ArtifactStatus.EXPIRED else ArtifactStatus.AVAILABLE,
)

data class WorkflowStep(val name: String, val conclusion: String?)

data class WorkflowJob(val id: Long, val name: String, val conclusion: String?, val steps: List<WorkflowStep>)

/** Maps GitHub's (status, conclusion) pair to [BuildStatus]. SUCCESS is returned ONLY for completed + success. */
object WorkflowStatusMapper {
    fun map(status: String?, conclusion: String?): BuildStatus = when (status) {
        "completed" -> when (conclusion) {
            "success" -> BuildStatus.SUCCESS
            "cancelled" -> BuildStatus.CANCELLED
            else -> BuildStatus.FAILED // failure, timed_out, startup_failure, action_required, skipped, stale, neutral, unknown
        }
        "in_progress" -> BuildStatus.RUNNING
        // queued, waiting, requested, pending and anything GitHub adds later: still not running, definitely not done.
        else -> BuildStatus.QUEUED
    }
}

/** Per-project cloud build settings (which repository/branch/folder to use). */
data class CloudBuildConfig(
    val projectId: String,
    val repoOwner: String,
    val repoName: String,
    val branch: String,
    /** Folder inside the repository that receives this project's files. */
    val projectDir: String,
    val gradleVersion: String = DEFAULT_GRADLE_VERSION,
) {
    val repoFullName: String get() = "$repoOwner/$repoName"

    companion object {
        const val DEFAULT_GRADLE_VERSION = "8.9"
        const val DEFAULT_DIR_PREFIX = "almus-projects"
        const val GRADLE_TASK = "assembleDebug"
        const val WORKFLOW_FILE = "almus-cloud-build.yml"
        const val WORKFLOW_PATH = ".github/workflows/$WORKFLOW_FILE"
        const val WORKFLOW_VERSION = 1

        /** The workflow file contains this exact marker line so the app can tell whether it is installed and current. */
        const val WORKFLOW_MARKER = "# almus-cloud-build-workflow-version: $WORKFLOW_VERSION"

        fun defaultDir(projectName: String): String {
            val slug = projectName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
            return "$DEFAULT_DIR_PREFIX/${slug.ifEmpty { "project" }}"
        }
    }
}

/** Tunable polling behavior. Defaults are conservative so GitHub rate limits are never a concern. */
data class PollingPolicy(
    val findRunTimeoutMs: Long = 120_000L,
    val findRunIntervalMs: Long = 4_000L,
    val initialIntervalMs: Long = 8_000L,
    val maxIntervalMs: Long = 30_000L,
    val overallTimeoutMs: Long = 45 * 60_000L,
    val maxConsecutiveErrors: Int = 5,
    val errorBackoffBaseMs: Long = 5_000L,
    /** If GitHub says the rate limit resets later than this, stop polling instead of sleeping. */
    val maxRateLimitWaitMs: Long = 5 * 60_000L,
)
