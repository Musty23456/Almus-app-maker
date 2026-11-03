package com.almus.appcreatorpro.service.monitor

import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.util.Formatters

/** The five things a user is told about. The declaration order is the order a build moves through them. */
enum class NotificationKind(val stage: String, val terminal: Boolean, val label: String) {
    STARTED("started", false, "Build started"),
    IN_PROGRESS("in_progress", false, "Build in progress"),
    SUCCESS("success", true, "Build succeeded"),
    FAILED("failed", true, "Build failed"),
    CANCELLED("cancelled", true, "Build cancelled"),
    ;

    companion object {
        fun fromStage(stage: String?): NotificationKind? = entries.firstOrNull { it.stage == stage }
    }
}

/** Everything a notification shows. Contains no token, key or password: only names and status. */
data class BuildNotification(
    val buildId: String,
    val kind: NotificationKind,
    /** Project name and what happened. */
    val title: String,
    /** Build type and current status. */
    val text: String,
    /** The same, plus the result, duration and repository (shown when the notification is expanded). */
    val bigText: String,
    val startedAtMillis: Long,
)

/** What to do about the current state of one build. [notification] null = remember the stage but show nothing. */
data class NotificationPlan(val kind: NotificationKind, val notification: BuildNotification?)

/** Pure rules: which notification (if any) a build's current state calls for, given the last stage already handled. */
object BuildNotificationPlanner {
    /** Older results are not announced (for example builds finished long before notifications existed). */
    const val STALE_MS: Long = 12 * 3_600_000L

    fun kindFor(status: BuildStatus): NotificationKind = when (status) {
        BuildStatus.PREPARING, BuildStatus.QUEUED -> NotificationKind.STARTED
        BuildStatus.RUNNING -> NotificationKind.IN_PROGRESS
        BuildStatus.SUCCESS -> NotificationKind.SUCCESS
        BuildStatus.FAILED -> NotificationKind.FAILED
        BuildStatus.CANCELLED -> NotificationKind.CANCELLED
    }

    fun plan(record: BuildRecord, lastStage: String?, nowMillis: Long): NotificationPlan? {
        if (record.isSample) return null
        val kind = kindFor(record.status)
        if (kind.stage == lastStage) return null
        val last = NotificationKind.fromStage(lastStage)
        if (last != null) {
            // A finished build never goes back, and a running build is never announced as "started" again.
            if (last.terminal) return null
            if (!kind.terminal && kind.ordinal < last.ordinal) return null
        } else {
            val reference = if (kind.terminal) record.finishedAtMillis ?: record.startedAtMillis else record.startedAtMillis
            if (nowMillis - reference > STALE_MS) return NotificationPlan(kind, null)
        }
        return NotificationPlan(kind, content(record, kind))
    }

    private fun content(record: BuildRecord, kind: NotificationKind): BuildNotification {
        val type = record.configuration?.shortLabel() ?: BuildVariant.DEBUG_APK.label
        val status = when (record.status) {
            BuildStatus.PREPARING -> "Preparing"
            BuildStatus.QUEUED -> "Queued"
            BuildStatus.RUNNING -> "Running"
            BuildStatus.SUCCESS -> "Succeeded"
            BuildStatus.FAILED -> "Failed"
            BuildStatus.CANCELLED -> "Cancelled"
        }
        val project = record.projectName.ifBlank { "Build" }
        val result = if (kind.terminal) "Result: ${record.detail?.takeIf { it.isNotBlank() } ?: kind.label}" else "Result: not finished yet"
        val lines = listOfNotNull(
            "Build type: $type",
            "Status: $status",
            result,
            record.durationSeconds?.let { "Duration: ${Formatters.formatDuration(it)}" },
            record.repoFullName?.takeIf { it.isNotBlank() }?.let { "Repository: $it" },
        )
        return BuildNotification(
            buildId = record.id,
            kind = kind,
            title = "$project - ${kind.label}",
            text = "$type - $status",
            bigText = lines.joinToString("\n"),
            startedAtMillis = record.startedAtMillis,
        )
    }
}
