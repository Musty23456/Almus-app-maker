package com.almus.appcreatorpro.ui.dashboard

import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.util.Formatters

enum class DashboardFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

enum class BuildGroup { ACTIVE, COMPLETED, FAILED, CANCELLED }

/** Can the build's output still be downloaded? */
enum class ArtifactAvailability { PENDING, AVAILABLE, ON_PHONE, EXPIRED, DELETED, NONE }

data class ArtifactSummary(val availability: ArtifactAvailability, val text: String)

data class DashboardItem(
    val record: BuildRecord,
    val group: BuildGroup,
    /** For example "Release AAB". Builds made before Phase 6 are debug APKs. */
    val buildType: String,
    val statusText: String,
    /** Final duration of a finished build, time running so far for an active one. */
    val durationSeconds: Int?,
    /** True while the app is following this build live. */
    val isLive: Boolean,
    /** Checking had stopped (connection lost, timeout, sign-in); refresh to continue. */
    val needsAttention: Boolean,
    val canCancel: Boolean,
    val artifact: ArtifactSummary,
) {
    val id: String get() = record.id
}

data class DashboardSummary(val active: Int, val completed: Int, val failed: Int, val cancelled: Int) {
    val total: Int get() = active + completed + failed + cancelled
}

data class DashboardState(
    val summary: DashboardSummary,
    val filter: DashboardFilter,
    /** Active builds shown under the current filter (ALL and ACTIVE). Newest first. */
    val active: List<DashboardItem>,
    /** Finished builds shown under the current filter: the build history. Newest first. */
    val history: List<DashboardItem>,
) {
    val isEmpty: Boolean get() = active.isEmpty() && history.isEmpty()
}

/** Pure: turns the stored build records into what the dashboard shows. */
object DashboardBuilder {
    fun build(
        records: List<BuildRecord>,
        nowMillis: Long,
        followed: Set<String>,
        savedOnPhone: Set<String>,
        filter: DashboardFilter,
    ): DashboardState {
        val items = records
            .filter { !it.isSample }
            .sortedByDescending { it.startedAtMillis }
            .map { toItem(it, nowMillis, followed, savedOnPhone) }

        val summary = DashboardSummary(
            active = items.count { it.group == BuildGroup.ACTIVE },
            completed = items.count { it.group == BuildGroup.COMPLETED },
            failed = items.count { it.group == BuildGroup.FAILED },
            cancelled = items.count { it.group == BuildGroup.CANCELLED },
        )
        val shown = when (filter) {
            DashboardFilter.ALL -> items
            DashboardFilter.ACTIVE -> items.filter { it.group == BuildGroup.ACTIVE }
            DashboardFilter.COMPLETED -> items.filter { it.group == BuildGroup.COMPLETED }
            DashboardFilter.FAILED -> items.filter { it.group == BuildGroup.FAILED }
            DashboardFilter.CANCELLED -> items.filter { it.group == BuildGroup.CANCELLED }
        }
        return DashboardState(
            summary = summary,
            filter = filter,
            active = shown.filter { it.group == BuildGroup.ACTIVE },
            history = shown.filter { it.group != BuildGroup.ACTIVE },
        )
    }

    fun toItem(record: BuildRecord, nowMillis: Long, followed: Set<String>, savedOnPhone: Set<String>): DashboardItem {
        val group = when (record.status) {
            BuildStatus.PREPARING, BuildStatus.QUEUED, BuildStatus.RUNNING -> BuildGroup.ACTIVE
            BuildStatus.SUCCESS -> BuildGroup.COMPLETED
            BuildStatus.FAILED -> BuildGroup.FAILED
            BuildStatus.CANCELLED -> BuildGroup.CANCELLED
        }
        val duration = record.durationSeconds
            ?: if (group == BuildGroup.ACTIVE) ((nowMillis - record.startedAtMillis) / 1000L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() else null
        val statusText = when {
            group == BuildGroup.ACTIVE && record.cancelRequested -> "Cancelling"
            else -> when (record.status) {
                BuildStatus.PREPARING -> "Preparing"
                BuildStatus.QUEUED -> "Queued"
                BuildStatus.RUNNING -> "Running"
                BuildStatus.SUCCESS -> "Succeeded"
                BuildStatus.FAILED -> "Failed"
                BuildStatus.CANCELLED -> "Cancelled"
            }
        }
        return DashboardItem(
            record = record,
            group = group,
            buildType = record.configuration?.shortLabel() ?: BuildVariant.DEBUG_APK.label,
            statusText = statusText,
            durationSeconds = duration,
            isLive = group == BuildGroup.ACTIVE && record.id in followed,
            needsAttention = group == BuildGroup.ACTIVE && record.pollingStopped,
            canCancel = group == BuildGroup.ACTIVE && !record.cancelRequested,
            artifact = artifactSummary(record, group, nowMillis, record.id in savedOnPhone),
        )
    }

    fun artifactSummary(record: BuildRecord, group: BuildGroup, nowMillis: Long, onPhone: Boolean): ArtifactSummary {
        if (group == BuildGroup.ACTIVE) return ArtifactSummary(ArtifactAvailability.PENDING, "Available when the build finishes")
        if (onPhone) return ArtifactSummary(ArtifactAvailability.ON_PHONE, "Saved on this phone")
        val files = record.artifacts.filter { it.isDeliverable }
        val usable = files.firstOrNull { it.effectiveStatus(nowMillis) == ArtifactStatus.AVAILABLE }
        return when {
            usable != null -> {
                val kind = if (usable.isBundle) "AAB" else "APK"
                ArtifactSummary(ArtifactAvailability.AVAILABLE, "$kind ready (${Formatters.formatBytes(usable.sizeBytes)})")
            }
            files.any { it.effectiveStatus(nowMillis) == ArtifactStatus.EXPIRED } ->
                ArtifactSummary(ArtifactAvailability.EXPIRED, "Expired on GitHub")
            files.any { it.effectiveStatus(nowMillis) == ArtifactStatus.DELETED } ->
                ArtifactSummary(ArtifactAvailability.DELETED, "Deleted on GitHub")
            else -> ArtifactSummary(ArtifactAvailability.NONE, "No APK or AAB")
        }
    }
}
