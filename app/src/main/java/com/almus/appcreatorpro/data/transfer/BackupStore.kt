package com.almus.appcreatorpro.data.transfer

import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.ProjectTransferRepository
import com.almus.appcreatorpro.domain.transfer.BackupInfo
import com.almus.appcreatorpro.domain.transfer.BackupKind
import com.almus.appcreatorpro.domain.transfer.ImportSourceKind
import com.almus.appcreatorpro.domain.transfer.ProjectNaming
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Project backups, kept in the app's private storage:
 *
 * ```
 * <rootDir>/<projectId>/<backupId>.zip          the project (same layout as an export)
 * <rootDir>/<projectId>/<backupId>.properties   name, type, package, date, label (written LAST)
 * ```
 *
 * A backup only exists once its .properties file is there, so a crash while writing never produces a backup that
 * looks complete. Every new zip is re-opened and checked before the backup is accepted. Backups are kept when their
 * project is deleted, so a deleted project can be restored.
 *
 * Overwriting is never silent: [restoreAsNew] creates a separate project, and [restoreReplacing] first makes a
 * safety backup of the current state.
 */
class BackupStore(
    private val rootDir: File,
    private val projects: ProjectRepository,
    private val transfer: ProjectTransferRepository,
    private val archiver: ProjectArchiver,
    private val importer: ProjectImportService,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { "b" + UUID.randomUUID().toString().replace("-", "").take(12) },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxBackupsPerProject: Int = DEFAULT_MAX_BACKUPS,
) {

    suspend fun createBackup(
        projectId: String,
        label: String? = null,
        kind: BackupKind = BackupKind.MANUAL,
        onProgress: (TransferProgress) -> Unit = {},
    ): BackupInfo {
        val project = projects.getProject(projectId) ?: throw TransferException(Kind.NOT_FOUND, "This project no longer exists.")
        if (!ID_REGEX.matches(projectId)) throw TransferException(Kind.NOT_FOUND, "This project no longer exists.")
        if (kind == BackupKind.MANUAL && listBackups(projectId).count { it.kind == BackupKind.MANUAL } >= maxBackupsPerProject) {
            throw TransferException(
                Kind.LIMIT_REACHED,
                "You already have $maxBackupsPerProject backups of this project. Delete an old one first.",
            )
        }
        val id = idGenerator()
        if (!ID_REGEX.matches(id)) throw TransferException(Kind.STORAGE, "Could not create a backup id.")
        val createdAt = clock()
        val cleanLabel = (label ?: defaultLabel(kind)).trim().take(MAX_LABEL)

        return withContext(ioDispatcher) {
            val dir = File(rootDir, projectId)
            if (!dir.isDirectory && !dir.mkdirs()) throw TransferException(Kind.STORAGE, "Could not create the backup folder.")
            val zip = File(dir, "$id.zip")
            val part = File(dir, "$id.zip.part")
            val meta = File(dir, "$id.properties")
            try {
                val summary = try {
                    FileOutputStream(part).use { out -> archiver.writeZip(projectId, out, onProgress) }
                } catch (e: IOException) {
                    throw TransferException(Kind.STORAGE, "Could not write the backup. Is the phone's storage full?", e)
                }
                verifyZip(part, summary.fileCount)
                try {
                    Files.move(part.toPath(), zip.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (e: IOException) {
                    throw TransferException(Kind.STORAGE, "Could not save the backup.", e)
                }
                val info = BackupInfo(
                    id = id,
                    projectId = projectId,
                    projectName = project.name,
                    type = project.type,
                    packageName = project.packageName,
                    description = project.description,
                    createdAtMillis = createdAt,
                    label = cleanLabel,
                    kind = kind,
                    fileCount = summary.fileCount,
                    zipBytes = zip.length(),
                )
                writeMeta(meta, info)
                info
            } catch (e: Throwable) {
                part.delete()
                zip.delete()
                meta.delete()
                throw e
            }
        }
    }

    /** Newest first. [projectId] null = every backup of every project (including deleted projects). */
    suspend fun listBackups(projectId: String? = null): List<BackupInfo> = withContext(ioDispatcher) {
        val dirs: List<File> = if (projectId != null) {
            if (!ID_REGEX.matches(projectId)) return@withContext emptyList<BackupInfo>()
            listOf(File(rootDir, projectId))
        } else {
            rootDir.listFiles { f -> f.isDirectory }.orEmpty().toList()
        }
        val result = ArrayList<BackupInfo>()
        for (dir in dirs) {
            val metas = dir.listFiles { f -> f.isFile && f.name.endsWith(META_SUFFIX) }.orEmpty()
            for (meta in metas) {
                val info: BackupInfo? = try {
                    readMeta(meta, dir.name)
                } catch (_: Exception) {
                    null
                }
                if (info != null && File(dir, "${info.id}.zip").isFile) result += info
            }
        }
        result.sortedByDescending { it.createdAtMillis }
    }

    suspend fun deleteBackup(backupId: String) {
        val info = find(backupId)
        withContext(ioDispatcher) {
            val dir = File(rootDir, info.projectId)
            File(dir, "${info.id}$META_SUFFIX").delete() // first: a half-deleted backup must not look complete
            File(dir, "${info.id}.zip").delete()
            if (dir.isDirectory && dir.list().isNullOrEmpty()) dir.delete()
        }
    }

    /** Restores into a NEW project; nothing existing is touched. */
    suspend fun restoreAsNew(backupId: String, onProgress: (TransferProgress) -> Unit = {}): Project {
        val info = find(backupId)
        val zip = zipOf(info)
        val staged = importer.stageZipFile(zip, info.projectName, ImportSourceKind.BACKUP, stripSingleRoot = false, onProgress = onProgress)
        val existing = projects.getProjects().map { it.name }
        val name = ProjectNaming.unique(ProjectNaming.sanitize("${info.projectName} restored"), existing)
        return try {
            importer.commitAs(staged, name, info.packageName, info.description, info.type)
        } catch (e: Throwable) {
            importer.discard(staged)
            throw e
        }
    }

    /**
     * Replaces the files of the ORIGINAL project with this backup. A safety backup of the current state is made
     * first; if the restore fails the project keeps its current files.
     */
    suspend fun restoreReplacing(backupId: String, onProgress: (TransferProgress) -> Unit = {}): Project {
        val info = find(backupId)
        if (projects.getProject(info.projectId) == null) {
            throw TransferException(Kind.NOT_FOUND, "The original project no longer exists. Restore the backup as a new project instead.")
        }
        val zip = zipOf(info)
        val staged = importer.stageZipFile(zip, info.projectName, ImportSourceKind.BACKUP, stripSingleRoot = false, onProgress = onProgress)
        try {
            createBackup(info.projectId, "Before restoring \"${info.label}\"", BackupKind.BEFORE_RESTORE)
            return transfer.replaceContents(info.projectId, staged.dir)
        } finally {
            importer.discard(staged)
        }
    }

    // ------------------------------------------------------------------------------------------ internals

    private suspend fun find(backupId: String): BackupInfo {
        if (!ID_REGEX.matches(backupId)) throw TransferException(Kind.NOT_FOUND, "This backup no longer exists.")
        return listBackups(null).firstOrNull { it.id == backupId }
            ?: throw TransferException(Kind.NOT_FOUND, "This backup no longer exists.")
    }

    private fun zipOf(info: BackupInfo): File {
        val zip = File(File(rootDir, info.projectId), "${info.id}.zip")
        if (!zip.isFile) throw TransferException(Kind.INVALID_BACKUP, "The backup file is missing.")
        return zip
    }

    private fun defaultLabel(kind: BackupKind): String = when (kind) {
        BackupKind.MANUAL -> "Backup"
        BackupKind.BEFORE_RESTORE -> "Before restore"
    }

    private fun verifyZip(file: File, expectedEntries: Int) {
        try {
            ZipFile(file).use { zip ->
                if (zip.size() != expectedEntries) {
                    throw TransferException(Kind.INVALID_BACKUP, "The backup could not be verified, so it was discarded.")
                }
            }
        } catch (e: IOException) {
            throw TransferException(Kind.INVALID_BACKUP, "The backup could not be verified, so it was discarded.", e)
        }
    }

    private fun writeMeta(meta: File, info: BackupInfo) {
        val props = Properties().apply {
            setProperty("format", "1")
            setProperty("id", info.id)
            setProperty("projectName", info.projectName)
            setProperty("type", info.type.id)
            setProperty("packageName", info.packageName)
            setProperty("description", info.description)
            setProperty("createdAt", info.createdAtMillis.toString())
            setProperty("label", info.label)
            setProperty("kind", info.kind.name)
            setProperty("fileCount", info.fileCount.toString())
            setProperty("zipBytes", info.zipBytes.toString())
        }
        val writer = StringWriter()
        props.store(writer, null)
        val tmp = File(meta.parentFile, meta.name + ".part")
        try {
            tmp.writeBytes(writer.toString().toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp.toPath(), meta.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw TransferException(Kind.STORAGE, "Could not save the backup information.", e)
        }
    }

    private fun readMeta(meta: File, projectId: String): BackupInfo? {
        val props = Properties()
        InputStreamReader(FileInputStream(meta), StandardCharsets.UTF_8).use { props.load(it) }
        val id = props.getProperty("id") ?: return null
        if (id != meta.name.removeSuffix(META_SUFFIX) || !ID_REGEX.matches(id)) return null
        val type = ProjectType.fromId(props.getProperty("type")) ?: return null
        return BackupInfo(
            id = id,
            projectId = projectId,
            projectName = props.getProperty("projectName") ?: return null,
            type = type,
            packageName = props.getProperty("packageName").orEmpty(),
            description = props.getProperty("description").orEmpty(),
            createdAtMillis = props.getProperty("createdAt")?.toLongOrNull() ?: return null,
            label = props.getProperty("label").orEmpty(),
            kind = runCatching { BackupKind.valueOf(props.getProperty("kind").orEmpty()) }.getOrDefault(BackupKind.MANUAL),
            fileCount = props.getProperty("fileCount")?.toIntOrNull() ?: 0,
            zipBytes = props.getProperty("zipBytes")?.toLongOrNull() ?: 0L,
        )
    }

    companion object {
        const val DEFAULT_MAX_BACKUPS = 30
        private const val MAX_LABEL = 60
        private const val META_SUFFIX = ".properties"
        private val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
