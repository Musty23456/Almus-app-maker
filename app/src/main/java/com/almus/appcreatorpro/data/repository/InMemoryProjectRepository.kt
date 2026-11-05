package com.almus.appcreatorpro.data.repository

import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.validation.PackageNameValidator
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * In-memory project store. Since Phase 2 the app uses
 * [com.almus.appcreatorpro.data.storage.FileProjectStore]; this class remains for fast unit tests.
 *
 * @param latencyMs artificial delay so the UI can show its loading state; use 0 in tests.
 */
class InMemoryProjectRepository(
    initialProjects: List<Project> = emptyList(),
    private val latencyMs: Long = 0L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ProjectRepository {

    private val projects = MutableStateFlow(initialProjects)

    override fun observeProjects(): Flow<List<Project>> =
        projects.map { list -> list.sortedByDescending { it.updatedAtMillis } }

    override suspend fun getProjects(): List<Project> {
        simulateLatency()
        return projects.value.sortedByDescending { it.updatedAtMillis }
    }

    override suspend fun getProject(id: String): Project? = projects.value.firstOrNull { it.id == id }

    override suspend fun createProject(
        name: String,
        packageName: String,
        description: String,
        type: ProjectType,
    ): Project {
        simulateLatency()
        val cleanName = name.trim()
        val cleanPackage = packageName.trim()
        ProjectNameValidator.validate(cleanName, projects.value.map { it.name }).errorMessage
            ?.let { throw ValidationException(it) }
        if (type.requiresPackage) {
            PackageNameValidator.validate(cleanPackage).errorMessage?.let { throw ValidationException(it) }
        }

        val now = clock()
        val project = Project(
            id = idGenerator(),
            name = cleanName,
            packageName = if (type.requiresPackage) cleanPackage else "",
            description = description.trim(),
            createdAtMillis = now,
            updatedAtMillis = now,
            type = type,
        )
        projects.value = projects.value + project
        return project
    }

    override suspend fun renameProject(id: String, newName: String): Project {
        simulateLatency()
        val current = projects.value.firstOrNull { it.id == id }
            ?: throw StorageException(StorageException.Kind.NOT_FOUND, "This project no longer exists.")
        val cleanName = newName.trim()
        ProjectNameValidator.validate(cleanName, projects.value.filter { it.id != id }.map { it.name }).errorMessage
            ?.let { throw ValidationException(it) }
        val updated = current.copy(name = cleanName, updatedAtMillis = clock())
        projects.value = projects.value.map { if (it.id == id) updated else it }
        return updated
    }

    override suspend fun deleteProject(id: String) {
        simulateLatency()
        projects.value = projects.value.filterNot { it.id == id }
    }

    private suspend fun simulateLatency() {
        if (latencyMs > 0) delay(latencyMs)
    }
}
