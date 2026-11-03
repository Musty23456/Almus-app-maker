package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.ProjectFileAccess

/** The files of ONE project, by project-relative path. The real implementation wraps `SourceFileRepository`. */
interface AiProjectFiles : ProjectFileAccess {
    /** All file paths of the project, sorted. */
    suspend fun list(): List<String>
    suspend fun exists(path: String): Boolean
    suspend fun delete(path: String)
}
