package com.almus.appcreatorpro.domain.transfer

import com.almus.appcreatorpro.domain.model.Project

/** Search, sorting and the "recent projects" list. Pure functions. */
object ProjectListFilter {
    const val DEFAULT_RECENT_COUNT = 5

    fun apply(
        projects: List<Project>,
        query: String,
        sort: ProjectSort,
        openedAt: Map<String, Long> = emptyMap(),
    ): List<Project> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matching = if (tokens.isEmpty()) projects else projects.filter { project ->
            val haystack = "${project.name} ${project.packageName} ${project.description} ${project.type.displayName}".lowercase()
            tokens.all { haystack.contains(it) }
        }
        val byName = compareBy<Project>({ it.name.lowercase() }, { it.id })
        return when (sort) {
            ProjectSort.RECENTLY_UPDATED -> matching.sortedWith(compareByDescending<Project> { it.updatedAtMillis }.then(byName))
            ProjectSort.RECENTLY_OPENED -> matching.sortedWith(
                compareByDescending<Project> { openedAt[it.id] ?: Long.MIN_VALUE }
                    .thenByDescending { it.updatedAtMillis }
                    .then(byName),
            )
            ProjectSort.NAME_AZ -> matching.sortedWith(byName)
            ProjectSort.NAME_ZA -> matching.sortedWith(byName.reversed())
            ProjectSort.NEWEST_CREATED -> matching.sortedWith(compareByDescending<Project> { it.createdAtMillis }.then(byName))
            ProjectSort.TYPE -> matching.sortedWith(compareBy<Project> { it.type.displayName }.then(byName))
        }
    }

    /** Projects that were opened at least once, newest first. */
    fun recent(projects: List<Project>, openedAt: Map<String, Long>, limit: Int = DEFAULT_RECENT_COUNT): List<Project> =
        projects.filter { openedAt.containsKey(it.id) }
            .sortedByDescending { openedAt[it.id] ?: 0L }
            .take(limit)
}
