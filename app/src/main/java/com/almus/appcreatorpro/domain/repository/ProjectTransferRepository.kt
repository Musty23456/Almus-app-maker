package com.almus.appcreatorpro.domain.repository

import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import java.io.File

/** Where a project keeps its data on disk. [extraDir] may not exist (a project without extra files). */
data class ProjectContentRoots(val sourceDir: File, val extraDir: File)

/**
 * Whole-project operations used by import, export and backup. A project's data is split in two folders:
 * `files/` (UTF-8 text the editor and cloud builds can handle) and `extra/` (images, jars, very large files and
 * everything else that must survive an export or backup but is not editable).
 */
interface ProjectTransferRepository {
    /**
     * Creates a project from a staging folder holding `files/` and optionally `extra/` (both are MOVED, not
     * copied). Throws [com.almus.appcreatorpro.domain.error.ValidationException] for a bad or duplicate name or
     * package; the staging folder is left untouched in that case so the caller can retry with another name.
     */
    suspend fun importStaged(
        stagedDir: File,
        name: String,
        packageName: String,
        description: String,
        type: ProjectType,
    ): Project

    /**
     * Replaces ALL files of an existing project with the staged ones. All or nothing: if anything fails the
     * project's previous files are put back.
     */
    suspend fun replaceContents(projectId: String, stagedDir: File): Project

    suspend fun contentRoots(projectId: String): ProjectContentRoots
}
