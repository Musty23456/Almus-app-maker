package com.almus.appcreatorpro.ui.home

import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus

/**
 * Numbers shown in the dashboard tiles. Sample builds (the placeholder rows of the early phases) are NOT counted, so the
 * dashboard never reports a success rate that was not earned by a real build.
 */
data class DashboardStats(
    val projects: Int,
    val realBuilds: Int,
    val succeeded: Int,
    val failed: Int,
    val running: Int,
) {
    val finished: Int get() = succeeded + failed

    /** Succeeded / (succeeded + failed) as a whole percent, or null while no real build has finished. */
    val successPercent: Int?
        get() = if (finished == 0) null else Math.round(succeeded * 100f / finished)

    companion object {
        fun from(projectCount: Int, builds: List<BuildRecord>): DashboardStats {
            val real = builds.filter { !it.isSample }
            return DashboardStats(
                projects = projectCount,
                realBuilds = real.size,
                succeeded = real.count { it.status == BuildStatus.SUCCESS },
                failed = real.count { it.status == BuildStatus.FAILED },
                running = real.count { it.status == BuildStatus.RUNNING || it.status == BuildStatus.QUEUED || it.status == BuildStatus.PREPARING },
            )
        }
    }
}

/** Greeting for the hero card. [hour] is 0-23 in the user's time zone. */
fun greetingForHour(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Welcome back"
}
