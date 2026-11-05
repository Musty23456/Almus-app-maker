package com.almus.appcreatorpro.domain.repository

import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.GitHubConnection
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.model.SourceFileEntry
import com.almus.appcreatorpro.domain.model.StorageInfo
import com.almus.appcreatorpro.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface ProjectRepository {
    /** Emits the current list (most recently updated first) and every later change. */
    fun observeProjects(): Flow<List<Project>>

    /** Human readable problems found while loading saved projects (e.g. a damaged project folder). */
    fun observeLoadIssues(): Flow<List<String>> = flowOf(emptyList())

    suspend fun getProjects(): List<Project>

    suspend fun getProject(id: String): Project?

    /**
     * Adds a project. Throws [com.almus.appcreatorpro.domain.error.ValidationException] for invalid
     * input and [com.almus.appcreatorpro.domain.error.StorageException] if it cannot be saved.
     */
    suspend fun createProject(
        name: String,
        packageName: String,
        description: String,
        type: ProjectType = ProjectType.ANDROID_KOTLIN,
    ): Project

    suspend fun renameProject(id: String, newName: String): Project

    /** Deleting an unknown id is a no-op. */
    suspend fun deleteProject(id: String)
}

/**
 * Source files of a project. Paths are relative to the project's source root and use '/'.
 * Future build engines (cloud or offline) read a project through this interface.
 */
interface SourceFileRepository {
    suspend fun listFiles(projectId: String): List<SourceFileEntry>

    suspend fun readFile(projectId: String, path: String): String

    /** Creates or overwrites. */
    suspend fun writeFile(projectId: String, path: String, content: String)

    /** Fails with ALREADY_EXISTS if the path is taken. Missing folders are created. */
    suspend fun createFile(projectId: String, path: String, content: String = "")

    suspend fun renameFile(projectId: String, fromPath: String, toPath: String)

    suspend fun deleteFile(projectId: String, path: String)

    companion object {
        /** Largest file the editor will open or save. */
        const val MAX_FILE_BYTES: Int = 512 * 1024
    }
}

interface BuildRepository {
    suspend fun getBuildHistory(): List<BuildRecord>
}

/** Build history that can be written to (real cloud builds, since Phase 3). */
interface BuildStore : BuildRepository {
    /** Emits the current list (newest first) and every later change. */
    fun observeBuilds(): Flow<List<BuildRecord>>

    suspend fun getBuild(id: String): BuildRecord?

    /** Inserts or replaces by id. */
    suspend fun saveBuild(record: BuildRecord)

    suspend fun deleteBuild(id: String)
}

interface GitHubRepository {
    suspend fun getConnection(): GitHubConnection
}

interface SettingsRepository {
    fun observeThemeMode(): Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    fun isWelcomeCompleted(): Boolean
    fun setWelcomeCompleted(completed: Boolean)
}

/** Reads device/app storage numbers. Implemented with Android APIs in the service layer. */
interface StorageInfoProvider {
    fun getStorageInfo(): StorageInfo
}
