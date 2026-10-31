package com.almus.appcreatorpro.data.sample

import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.domain.model.Project

/** Local sample data used by Phase 1. Everything here is fake and clearly labelled as a sample in the UI. */
object SampleData {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    fun projects(nowMillis: Long): List<Project> = listOf(
        Project(
            id = "sample-1",
            name = "Weather Buddy",
            packageName = "com.example.weatherbuddy",
            description = "Sample: a simple forecast app.",
            createdAtMillis = nowMillis - 12 * DAY,
            updatedAtMillis = nowMillis - 2 * HOUR,
        ),
        Project(
            id = "sample-2",
            name = "Habit Tracker",
            packageName = "com.example.habittracker",
            description = "Sample: daily habit streaks.",
            createdAtMillis = nowMillis - 30 * DAY,
            updatedAtMillis = nowMillis - 1 * DAY,
        ),
        Project(
            id = "sample-3",
            name = "Notes Lite",
            packageName = "com.example.noteslite",
            description = "Sample: minimal note taking.",
            createdAtMillis = nowMillis - 45 * DAY,
            updatedAtMillis = nowMillis - 6 * DAY,
        ),
    )

    fun builds(nowMillis: Long): List<BuildRecord> = listOf(
        BuildRecord(
            id = "build-4",
            projectName = "Weather Buddy",
            status = BuildStatus.RUNNING,
            startedAtMillis = nowMillis - 3 * MINUTE,
            durationSeconds = null,
            summary = "Sample: compiling Kotlin sources",
        ),
        BuildRecord(
            id = "build-3",
            projectName = "Weather Buddy",
            status = BuildStatus.SUCCESS,
            startedAtMillis = nowMillis - 2 * HOUR,
            durationSeconds = 214,
            summary = "Sample: debug APK ready",
        ),
        BuildRecord(
            id = "build-2",
            projectName = "Habit Tracker",
            status = BuildStatus.FAILED,
            startedAtMillis = nowMillis - 1 * DAY,
            durationSeconds = 97,
            summary = "Sample: unresolved reference in HabitScreen.kt",
        ),
        BuildRecord(
            id = "build-1",
            projectName = "Notes Lite",
            status = BuildStatus.SUCCESS,
            startedAtMillis = nowMillis - 6 * DAY,
            durationSeconds = 188,
            summary = "Sample: debug APK ready",
        ),
    )
}
