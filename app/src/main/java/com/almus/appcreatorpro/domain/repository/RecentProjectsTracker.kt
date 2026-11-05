package com.almus.appcreatorpro.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Remembers when each project was last opened in the editor (project id -> epoch millis). */
interface RecentProjectsTracker {
    val opened: StateFlow<Map<String, Long>>

    fun markOpened(projectId: String)

    fun forget(projectId: String)
}

/** Does nothing; the default for code (and older tests) that does not care about recents. */
object NoRecentProjects : RecentProjectsTracker {
    override val opened: StateFlow<Map<String, Long>> = MutableStateFlow(emptyMap())
    override fun markOpened(projectId: String) = Unit
    override fun forget(projectId: String) = Unit
}
