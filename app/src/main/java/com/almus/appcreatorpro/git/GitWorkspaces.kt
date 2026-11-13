package com.almus.appcreatorpro.git

import com.almus.appcreatorpro.data.storage.KeyValueStore
import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.ProjectTransferRepository
import com.almus.appcreatorpro.domain.transfer.ProjectDetector
import com.almus.appcreatorpro.domain.transfer.ProjectNaming
import java.io.File
import java.nio.file.Files
import java.util.UUID

fun interface GitWorkspaceResolver {
    suspend fun resolve(projectId: String): GitWorkspace
}

/** `<project>/files` is the work tree, `<project>/git` holds the repository. Deleting the project deletes both. */
class ProjectGitWorkspaceResolver(private val transfer: ProjectTransferRepository) : GitWorkspaceResolver {
    override suspend fun resolve(projectId: String): GitWorkspace {
        val roots = try {
            transfer.contentRoots(projectId)
        } catch (e: StorageException) {
            throw GitException(GitException.Kind.NOT_FOUND, "This project no longer exists.")
        }
        return GitWorkspace(workTree = roots.sourceDir, gitDir = File(roots.sourceDir.parentFile, GIT_DIR_NAME))
    }

    companion object {
        const val GIT_DIR_NAME = "git"
    }
}

/** Name and email used for commits. Stored in private preferences; contains no secret. */
class GitIdentityStore(private val meta: KeyValueStore) {
    fun get(): GitIdentity? {
        val name = meta.getString(NAME)?.takeIf { it.isNotBlank() } ?: return null
        val email = meta.getString(EMAIL)?.takeIf { it.isNotBlank() } ?: return null
        return GitIdentity(name, email)
    }

    fun set(identity: GitIdentity) {
        meta.putString(NAME, identity.name)
        meta.putString(EMAIL, identity.email)
    }

    private companion object {
        const val NAME = "git_user_name"
        const val EMAIL = "git_user_email"
    }
}

/** Turns a finished clone (a staging folder with `files/` and `git/`) into an ordinary project. */
interface GitCloneTarget {
    fun newStagingDir(): File
    suspend fun createProject(stagedDir: File, suggestedName: String): Project
}

class ProjectGitCloneTarget(
    private val stagingRoot: File,
    private val projects: ProjectRepository,
    private val transfer: ProjectTransferRepository,
) : GitCloneTarget {

    override fun newStagingDir(): File {
        val dir = File(stagingRoot, "clone-" + UUID.randomUUID().toString().filter { it.isLetterOrDigit() }.take(16))
        if (!dir.mkdirs()) throw GitException(GitException.Kind.UNKNOWN, "Could not prepare temporary storage on this phone.")
        return dir
    }

    override suspend fun createProject(stagedDir: File, suggestedName: String): Project {
        val filesDir = File(stagedDir, "files")
        val gitDir = File(stagedDir, ProjectGitWorkspaceResolver.GIT_DIR_NAME)
        if (!filesDir.isDirectory || !gitDir.isDirectory) throw GitException(GitException.Kind.UNKNOWN, "The clone did not produce a repository.")
        val paths = filesDir.walkTopDown().filter { it.isFile }.map { it.relativeTo(filesDir).invariantSeparatorsPath }.toList()
        val detection = ProjectDetector.detect(paths) { p ->
            try { File(filesDir, p).takeIf { it.length() < 512 * 1024 }?.readText(Charsets.UTF_8) } catch (e: Exception) { null }
        }
        val type: ProjectType = detection.type
            ?: throw GitException(GitException.Kind.INVALID_INPUT, detection.problem ?: "This repository is not a project type the app supports.")
        val name = ProjectNaming.unique(ProjectNaming.sanitize(suggestedName), projects.getProjects().map { it.name })
        val pkg = if (type.requiresPackage) detection.packageName.ifBlank { ProjectDetector.FALLBACK_PACKAGE } else ""
        val project = try {
            transfer.importStaged(stagedDir, name, pkg, "Cloned with Git", type)
        } catch (e: StorageException) {
            throw GitException(GitException.Kind.UNKNOWN, e.message ?: "The project could not be saved.")
        }
        try {
            val target = File(transfer.contentRoots(project.id).sourceDir.parentFile, ProjectGitWorkspaceResolver.GIT_DIR_NAME)
            try {
                Files.move(gitDir.toPath(), target.toPath())
            } catch (e: java.io.IOException) {
                if (!gitDir.copyRecursively(target, overwrite = false)) throw e
                gitDir.deleteRecursively()
            }
        } catch (e: Exception) {
            projects.deleteProject(project.id) // never leave a project whose history is missing
            throw GitException(GitException.Kind.UNKNOWN, "The repository could not be saved on this phone.")
        }
        return project
    }
}
