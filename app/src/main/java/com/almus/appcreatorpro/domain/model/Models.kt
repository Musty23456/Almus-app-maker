package com.almus.appcreatorpro.domain.model

/** What kind of project this is. Decides the starter files and which fields are required. */
enum class ProjectType(val id: String, val displayName: String, val requiresPackage: Boolean) {
    ANDROID_KOTLIN("android_kotlin", "Native Android (Kotlin)", true),
    ANDROID_JAVA("android_java", "Native Android (Java)", true),
    WEB("web", "HTML / CSS / JavaScript", false),
    ;

    companion object {
        /** Stable id used in storage; returns null for unknown ids so corrupted data can be detected. */
        fun fromId(id: String?): ProjectType? = entries.firstOrNull { it.id == id }
    }
}

/** A coding project managed by the app. Since Phase 2 projects are stored on the device. */
data class Project(
    val id: String,
    val name: String,
    /** Empty for project types that have no package (web). */
    val packageName: String,
    val description: String = "",
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val type: ProjectType = ProjectType.ANDROID_KOTLIN,
)

/** One source file inside a project; [path] is relative, uses '/' and never starts with '/'. */
data class SourceFileEntry(
    val path: String,
    val sizeBytes: Long,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * PREPARING = the app is still validating/uploading/dispatching (GitHub has not started a run yet).
 * QUEUED / RUNNING / SUCCESS / FAILED / CANCELLED mirror the GitHub Actions run once it exists.
 */
enum class BuildStatus {
    PREPARING, QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == SUCCESS || this == FAILED || this == CANCELLED
}

/**
 * One cloud build entry. Phase 1/2 had only SAMPLE records ([isSample] = true); since Phase 3 real builds
 * carry the GitHub details below ([isSample] = false).
 */
data class BuildRecord(
    val id: String,
    val projectName: String,
    val status: BuildStatus,
    val startedAtMillis: Long,
    val durationSeconds: Int?,
    val summary: String,
    val isSample: Boolean = true,
    val projectId: String? = null,
    val repoFullName: String? = null,
    val branch: String? = null,
    val projectDir: String? = null,
    val commitSha: String? = null,
    val runId: Long? = null,
    val runUrl: String? = null,
    /** Short note shown under the status (current step, failing step, why polling stopped...). */
    val detail: String? = null,
    /** Excerpt of the failing log lines, when GitHub reported a failure. */
    val errorExcerpt: String? = null,
    val artifactNames: List<String> = emptyList(),
    /** Phase 4: artifacts as last reported by GitHub (size, expiry, availability). */
    val artifacts: List<BuildArtifactInfo> = emptyList(),
    val finishedAtMillis: Long? = null,
    /** True when the app stopped polling (timeout, lost connection, expired sign-in) before a final state. */
    val pollingStopped: Boolean = false,
    /** Phase 6: what was asked for (build type, overrides). Null for builds made before Phase 6 = debug APK. */
    val configuration: com.almus.appcreatorpro.domain.build.BuildConfiguration? = null,
    /** Phase 6: true after the user asked to cancel and GitHub has not reported the end of the run yet. */
    val cancelRequested: Boolean = false,
)

/** Whether a build artifact can still be downloaded from GitHub. */
enum class ArtifactStatus {
    AVAILABLE,

    /** GitHub says it expired (artifacts are kept for a limited number of days). */
    EXPIRED,

    /** GitHub no longer has it (deleted, or the run was removed). */
    DELETED,
}

/** What the app knows about one artifact of a build's workflow run. Cached on the [BuildRecord]. */
data class BuildArtifactInfo(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expiresAtMillis: Long? = null,
    val status: ArtifactStatus = ArtifactStatus.AVAILABLE,
) {
    /** Takes the clock into account, so an artifact whose expiry date passed is reported as expired even before GitHub is asked again. */
    fun effectiveStatus(nowMillis: Long): ArtifactStatus =
        if (status == ArtifactStatus.AVAILABLE && expiresAtMillis != null && nowMillis >= expiresAtMillis) ArtifactStatus.EXPIRED else status

    val kind: com.almus.appcreatorpro.domain.build.ArtifactKind
        get() = com.almus.appcreatorpro.domain.build.ArtifactNaming.kindOf(name)

    val isApk: Boolean get() = name.startsWith(APK_PREFIX)

    /** Phase 6: an Android App Bundle (almus-aab-...). */
    val isBundle: Boolean get() = name.startsWith(AAB_PREFIX)

    /** An APK or an AAB: the files a user wants to download. Logs and hand-over files are not deliverables. */
    val isDeliverable: Boolean get() = isApk || isBundle

    companion object {
        const val APK_PREFIX = "almus-apk-"
        const val AAB_PREFIX = "almus-aab-"
    }
}

/** State of the GitHub link. Phase 1 can only ever be [NotConnected]. */
sealed interface GitHubConnection {
    data object NotConnected : GitHubConnection
    data class Connected(val username: String) : GitHubConnection
}

data class StorageInfo(
    val totalBytes: Long,
    val freeBytes: Long,
    val appDataBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - freeBytes).coerceAtLeast(0L)

    /** Fraction of device storage in use, always within 0f..1f. */
    val usedFraction: Float
        get() = if (totalBytes <= 0L) 0f else (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
