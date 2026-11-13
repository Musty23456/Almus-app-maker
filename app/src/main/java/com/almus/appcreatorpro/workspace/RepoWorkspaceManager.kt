package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.data.transfer.BackupStore
import com.almus.appcreatorpro.data.transfer.ProjectImportService
import com.almus.appcreatorpro.domain.github.CommitSummary
import com.almus.appcreatorpro.domain.github.GitHubRepo
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.ProjectTransferRepository
import com.almus.appcreatorpro.domain.transfer.BackupKind
import com.almus.appcreatorpro.domain.transfer.ImportSourceKind
import com.almus.appcreatorpro.domain.transfer.ProjectNaming
import com.almus.appcreatorpro.domain.transfer.StagedProject
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import java.util.UUID

/** Result of [RepoWorkspaceManager.sync]. */
sealed interface SyncResult {
    /** The local copy already matches the newest commit of the branch. Nothing was changed. */
    data class UpToDate(val workspace: RepoWorkspace) : SyncResult

    /** The local files were replaced with [workspace]'s commit. [backupLabel] names the safety copy made first. */
    data class Updated(val workspace: RepoWorkspace, val backupLabel: String) : SyncResult
}

/**
 * Brings a GitHub repository onto the phone and keeps that copy current.
 *
 * Download, unpack and project creation reuse the existing import pipeline ([ProjectImportService]: zip-slip guard, size
 * and file limits, project detection), so a workspace is an ordinary local project. Nothing here talks to the AI and
 * nothing here edits project files except [sync], which replaces them only after a safety backup succeeded.
 */
class RepoWorkspaceManager(
    private val github: WorkspaceGitHub,
    private val importer: ProjectImportService,
    private val transfer: ProjectTransferRepository,
    private val projects: ProjectRepository,
    private val backups: BackupStore,
    private val store: WorkspaceStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { "w" + UUID.randomUUID().toString().replace("-", "").take(12) },
) {

    /** True when the local project behind [workspace] still exists. */
    suspend fun projectExists(workspace: RepoWorkspace): Boolean = projects.getProject(workspace.projectId) != null

    /**
     * Opens the workspace for [repo] at [branchName], downloading it first if this phone does not have it yet.
     * An existing workspace is returned as it is (no download, local edits untouched); use [sync] to update it.
     */
    suspend fun openOrImport(
        repo: GitHubRepo,
        branchName: String,
        onProgress: (TransferProgress) -> Unit = {},
    ): RepoWorkspace {
        val existing = store.find(repo.owner, repo.name, branchName)
        if (existing != null) {
            if (projectExists(existing)) {
                store.markOpened(existing.id)
                return store.get(existing.id) ?: existing
            }
            // The local project was deleted: forget the record and import again.
            store.remove(existing.id)
        }

        val commit = github.latestCommit(repo.owner, repo.name, branchName)
        val staged = stage(repo.owner, repo.name, repo.name, commit, branchName, onProgress)
        val base = if (branchName == repo.defaultBranch) repo.name else "${repo.name} $branchName"
        val name = ProjectNaming.unique(ProjectNaming.sanitize(base), projects.getProjects().map { it.name })
        val project = try {
            importer.commit(staged, name)
        } catch (e: Throwable) {
            importer.discard(staged)
            throw e
        }
        val now = clock()
        val workspace = RepoWorkspace(
            id = idGenerator(),
            owner = repo.owner,
            repoName = repo.name,
            branch = branchName,
            commitSha = commit.sha,
            commitTitle = commit.title,
            isPrivate = repo.isPrivate,
            defaultBranch = repo.defaultBranch,
            description = repo.description,
            htmlUrl = repo.htmlUrl,
            projectId = project.id,
            importedAtMillis = now,
            lastOpenedAtMillis = now,
            lastSyncedAtMillis = now,
        )
        store.save(workspace)
        store.setActive(workspace.id)
        return workspace
    }

    /** The newest commit of the workspace's branch (a cheap check; nothing is downloaded). */
    suspend fun latestCommit(workspace: RepoWorkspace): CommitSummary =
        github.latestCommit(workspace.owner, workspace.repoName, workspace.branch)

    /**
     * Updates the local copy to the newest commit of its branch. The caller must have asked the user first: this REPLACES
     * the project's files, including edits made on the phone. Order of events, each step only after the previous worked:
     * newest commit -> download -> unpack and check -> safety backup -> replace files -> update the record.
     * If anything fails before the replace, the local files are exactly as they were.
     */
    suspend fun sync(workspace: RepoWorkspace, onProgress: (TransferProgress) -> Unit = {}): SyncResult {
        if (!projectExists(workspace)) {
            throw TransferException(Kind.NOT_FOUND, "The local copy of ${workspace.fullName} was deleted. Import it again.")
        }
        val latest = github.latestCommit(workspace.owner, workspace.repoName, workspace.branch)
        if (latest.sha == workspace.commitSha) return SyncResult.UpToDate(workspace)

        val staged = stage(workspace.owner, workspace.repoName, workspace.repoName, latest, workspace.branch, onProgress)
        try {
            val label = "Before sync to ${latest.shortSha}"
            // If the safety copy cannot be made, this throws and nothing is replaced.
            backups.createBackup(workspace.projectId, label, BackupKind.BEFORE_RESTORE)
            transfer.replaceContents(workspace.projectId, staged.dir)
            val now = clock()
            val updated = workspace.copy(
                commitSha = latest.sha,
                commitTitle = latest.title,
                lastSyncedAtMillis = now,
                lastOpenedAtMillis = now,
            )
            store.save(updated)
            return SyncResult.Updated(updated, label)
        } finally {
            importer.discard(staged)
        }
    }

    /** Forgets the workspace. The local project and its files stay (they are an ordinary project). */
    fun forget(workspace: RepoWorkspace) = store.remove(workspace.id)

    // ------------------------------------------------------------------------------------------ internals

    /** Downloads exactly [commit] and unpacks it into a staging folder, checked but not saved anywhere. */
    private suspend fun stage(
        owner: String,
        repoName: String,
        sourceName: String,
        commit: CommitSummary,
        branchName: String,
        onProgress: (TransferProgress) -> Unit,
    ): StagedProject {
        val tmp = importer.newTempFile("workspace-", ".zip")
        try {
            github.download(owner, repoName, commit.sha, tmp, onProgress)
            val note = "Imported from GitHub: $owner/$repoName (branch $branchName, commit ${commit.shortSha})"
            val staged = importer.stageZipFile(
                file = tmp,
                sourceName = sourceName,
                kind = ImportSourceKind.GITHUB,
                stripSingleRoot = true,
                originNote = note,
                onProgress = onProgress,
            )
            if (!staged.preview.canImport) {
                val reason = staged.preview.problems.firstOrNull() ?: "This repository has no supported project."
                importer.discard(staged)
                throw TransferException(Kind.UNSUPPORTED_PROJECT, reason)
            }
            return staged
        } finally {
            tmp.delete()
        }
    }
}
