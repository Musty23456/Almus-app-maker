package com.almus.appcreatorpro.data.storage

import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.error.StorageException.Kind
import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.model.SourceFileEntry
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.domain.template.ProjectTemplates
import com.almus.appcreatorpro.domain.validation.FilePathValidator
import com.almus.appcreatorpro.domain.validation.PackageNameValidator
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID

/**
 * Stores projects in app-private storage (no storage permission needed):
 *
 * ```
 * <rootDir>/<projectId>/project.properties   metadata (written last when a project is created)
 * <rootDir>/<projectId>/files/...            the project's source files
 * ```
 *
 * Writes go to a temporary file first and are then moved into place, so a crash or full disk
 * never leaves a half-written file behind. Projects whose metadata is missing or unreadable are
 * skipped and reported through [observeLoadIssues] instead of crashing the app.
 *
 * Disk work runs on [ioDispatcher] and is serialized by one lock.
 */
class FileProjectStore(
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProjectRepository, SourceFileRepository {

    private val lock = Any()
    private var loaded = false
    private val projects = MutableStateFlow<List<Project>>(emptyList())
    private val issues = MutableStateFlow<List<String>>(emptyList())

    // ---------------------------------------------------------------- ProjectRepository

    override fun observeProjects(): Flow<List<Project>> = flow {
        io { }
        emitAll(projects.map { list -> list.sortedByDescending { it.updatedAtMillis } })
    }

    override fun observeLoadIssues(): Flow<List<String>> = flow {
        io { }
        emitAll(issues)
    }

    override suspend fun getProjects(): List<Project> =
        io { projects.value.sortedByDescending { it.updatedAtMillis } }

    override suspend fun getProject(id: String): Project? =
        io { projects.value.firstOrNull { it.id == id } }

    override suspend fun createProject(
        name: String,
        packageName: String,
        description: String,
        type: ProjectType,
    ): Project = io {
        val cleanName = name.trim()
        val cleanPackage = packageName.trim()
        ProjectNameValidator.validate(cleanName, projects.value.map { it.name }).errorMessage
            ?.let { throw ValidationException(it) }
        val pkg = if (type.requiresPackage) {
            PackageNameValidator.validate(cleanPackage).errorMessage?.let { throw ValidationException(it) }
            cleanPackage
        } else {
            ""
        }

        val id = idGenerator()
        if (!ID_REGEX.matches(id)) throw StorageException(Kind.IO, "Could not create a project id.")
        val dir = File(rootDir, id)
        if (dir.exists()) throw StorageException(Kind.ALREADY_EXISTS, "A project folder with this id already exists.")

        val now = clock()
        val project = Project(id, cleanName, pkg, description.trim(), now, now, type)
        try {
            val sourceDir = File(dir, SOURCE_DIR)
            if (!sourceDir.mkdirs()) throw IOException("Could not create the project folder.")
            for ((path, content) in ProjectTemplates.filesFor(cleanName, pkg, type)) {
                val file = resolveIn(sourceDir, path)
                file.parentFile?.mkdirs()
                atomicWrite(file, content)
            }
            writeMeta(dir, project) // last: a project only "exists" once its metadata is complete
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        projects.value = projects.value + project
        project
    }

    override suspend fun renameProject(id: String, newName: String): Project = io {
        val current = projects.value.firstOrNull { it.id == id }
            ?: throw StorageException(Kind.NOT_FOUND, "This project no longer exists.")
        val cleanName = newName.trim()
        ProjectNameValidator.validate(cleanName, projects.value.filter { it.id != id }.map { it.name }).errorMessage
            ?.let { throw ValidationException(it) }
        val updated = current.copy(name = cleanName, updatedAtMillis = clock())
        writeMeta(File(rootDir, id), updated)
        projects.value = projects.value.map { if (it.id == id) updated else it }
        updated
    }

    override suspend fun deleteProject(id: String) = io {
        if (!ID_REGEX.matches(id)) return@io
        val dir = File(rootDir, id)
        if (dir.exists() && !dir.deleteRecursively()) {
            throw StorageException(Kind.IO, "Could not delete the project files. Try again.")
        }
        projects.value = projects.value.filterNot { it.id == id }
    }

    // ---------------------------------------------------------------- SourceFileRepository

    override suspend fun listFiles(projectId: String): List<SourceFileEntry> = io {
        val sourceDir = sourceDirOf(projectId)
        if (!sourceDir.isDirectory) return@io emptyList()
        sourceDir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(FilePathValidator.RESERVED_SUFFIX) }
            .map { SourceFileEntry(it.relativeTo(sourceDir).invariantSeparatorsPath, it.length()) }
            .sortedBy { it.path.lowercase() }
            .toList()
    }

    override suspend fun readFile(projectId: String, path: String): String = io {
        val file = resolveIn(sourceDirOf(projectId), path)
        if (!file.isFile) throw StorageException(Kind.NOT_FOUND, "'$path' no longer exists.")
        if (file.length() > SourceFileRepository.MAX_FILE_BYTES) {
            throw StorageException(Kind.TOO_LARGE, "'${file.name}' is too large to edit (limit ${SourceFileRepository.MAX_FILE_BYTES / 1024} KB).")
        }
        val bytes = file.readBytes()
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw StorageException(Kind.CORRUPTED, "'${file.name}' is not a UTF-8 text file.", e)
        }
    }

    override suspend fun writeFile(projectId: String, path: String, content: String) = io {
        checkSize(path, content)
        val file = resolveIn(sourceDirOf(projectId), path)
        if (file.isDirectory) throw StorageException(Kind.ALREADY_EXISTS, "'$path' is a folder.")
        file.parentFile?.mkdirs()
        atomicWrite(file, content)
        touch(projectId)
    }

    override suspend fun createFile(projectId: String, path: String, content: String) = io {
        checkSize(path, content)
        val sourceDir = sourceDirOf(projectId)
        val file = resolveIn(sourceDir, path)
        if (file.exists()) throw StorageException(Kind.ALREADY_EXISTS, "'$path' already exists.")
        ensureNoFileInParents(sourceDir, file)
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create folder for '$path'.")
        }
        atomicWrite(file, content)
        touch(projectId)
    }

    override suspend fun renameFile(projectId: String, fromPath: String, toPath: String) = io {
        val sourceDir = sourceDirOf(projectId)
        val from = resolveIn(sourceDir, fromPath)
        val to = resolveIn(sourceDir, toPath)
        if (!from.isFile) throw StorageException(Kind.NOT_FOUND, "'$fromPath' no longer exists.")
        if (fromPath == toPath) return@io
        if (to.exists()) throw StorageException(Kind.ALREADY_EXISTS, "'$toPath' already exists.")
        ensureNoFileInParents(sourceDir, to)
        val parent = to.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create folder for '$toPath'.")
        }
        Files.move(from.toPath(), to.toPath())
        pruneEmptyDirs(from.parentFile, sourceDir)
        touch(projectId)
    }

    override suspend fun deleteFile(projectId: String, path: String) = io {
        val sourceDir = sourceDirOf(projectId)
        val file = resolveIn(sourceDir, path)
        if (!file.isFile) throw StorageException(Kind.NOT_FOUND, "'$path' no longer exists.")
        if (!file.delete()) throw StorageException(Kind.IO, "Could not delete '$path'.")
        pruneEmptyDirs(file.parentFile, sourceDir)
        touch(projectId)
    }

    // ---------------------------------------------------------------- internals

    /** Runs [block] on the IO dispatcher under the lock; loads saved projects first if needed. */
    private suspend fun <T> io(block: () -> T): T = withContext(ioDispatcher) {
        synchronized(lock) {
            try {
                ensureLoaded()
                block()
            } catch (e: StorageException) {
                throw e
            } catch (e: ValidationException) {
                throw e
            } catch (e: IOException) {
                throw StorageException(Kind.IO, "Storage error: ${e.message ?: "unknown problem"}", e)
            } catch (e: SecurityException) {
                throw StorageException(Kind.IO, "Storage is not accessible: ${e.message ?: "permission denied"}", e)
            }
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        val found = mutableListOf<Project>()
        val problems = mutableListOf<String>()
        val dirs = rootDir.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
        for (dir in dirs) {
            val meta = File(dir, META_FILE)
            if (!meta.isFile) {
                problems += "Folder '${dir.name}' has no project information and was skipped."
                continue
            }
            try {
                found += readMeta(dir, meta)
            } catch (e: Exception) {
                problems += "Project folder '${dir.name}' is damaged and was skipped (${e.message ?: "unreadable"})."
            }
        }
        projects.value = found
        issues.value = problems
        loaded = true
    }

    private fun readMeta(dir: File, meta: File): Project {
        val props = Properties()
        InputStreamReader(FileInputStream(meta), StandardCharsets.UTF_8).use { props.load(it) }
        fun required(key: String): String = props.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: throw StorageException(Kind.CORRUPTED, "missing '$key'")

        val id = required("id")
        if (id != dir.name) throw StorageException(Kind.CORRUPTED, "id does not match folder")
        val type = ProjectType.fromId(required("type"))
            ?: throw StorageException(Kind.CORRUPTED, "unknown project type")
        val created = required("createdAt").toLongOrNull()
            ?: throw StorageException(Kind.CORRUPTED, "bad creation date")
        val updated = required("updatedAt").toLongOrNull()
            ?: throw StorageException(Kind.CORRUPTED, "bad update date")
        return Project(
            id = id,
            name = required("name"),
            packageName = props.getProperty("packageName").orEmpty(),
            description = props.getProperty("description").orEmpty(),
            createdAtMillis = created,
            updatedAtMillis = updated,
            type = type,
        )
    }

    private fun writeMeta(dir: File, project: Project) {
        val props = Properties().apply {
            setProperty("format", "1")
            setProperty("id", project.id)
            setProperty("name", project.name)
            setProperty("packageName", project.packageName)
            setProperty("description", project.description)
            setProperty("type", project.type.id)
            setProperty("createdAt", project.createdAtMillis.toString())
            setProperty("updatedAt", project.updatedAtMillis.toString())
        }
        val writer = StringWriter()
        props.store(writer, null)
        atomicWrite(File(dir, META_FILE), writer.toString())
    }

    /** Best effort: a failure to bump the "last updated" time must not fail a save that succeeded. */
    private fun touch(projectId: String) {
        val current = projects.value.firstOrNull { it.id == projectId } ?: return
        val updated = current.copy(updatedAtMillis = maxOf(clock(), current.updatedAtMillis))
        try {
            writeMeta(File(rootDir, projectId), updated)
            projects.value = projects.value.map { if (it.id == projectId) updated else it }
        } catch (_: IOException) {
            // ignored on purpose
        }
    }

    private fun sourceDirOf(projectId: String): File {
        if (!ID_REGEX.matches(projectId) || projects.value.none { it.id == projectId }) {
            throw StorageException(Kind.NOT_FOUND, "This project no longer exists.")
        }
        return File(File(rootDir, projectId), SOURCE_DIR)
    }

    private fun resolveIn(sourceDir: File, path: String): File {
        FilePathValidator.validate(path).errorMessage?.let { throw StorageException(Kind.INVALID_NAME, it) }
        val file = File(sourceDir, path)
        if (!file.canonicalPath.startsWith(sourceDir.canonicalPath + File.separator)) {
            throw StorageException(Kind.INVALID_NAME, "That path is outside the project.")
        }
        return file
    }

    private fun checkSize(path: String, content: String) {
        if (content.toByteArray(StandardCharsets.UTF_8).size > SourceFileRepository.MAX_FILE_BYTES) {
            throw StorageException(Kind.TOO_LARGE, "'$path' is too large (limit ${SourceFileRepository.MAX_FILE_BYTES / 1024} KB).")
        }
    }

    private fun ensureNoFileInParents(sourceDir: File, file: File) {
        var parent = file.parentFile
        while (parent != null && parent != sourceDir) {
            if (parent.isFile) {
                throw StorageException(Kind.ALREADY_EXISTS, "'${parent.name}' is a file, so it cannot contain other files.")
            }
            parent = parent.parentFile
        }
    }

    private fun pruneEmptyDirs(start: File?, stopAt: File) {
        var dir = start
        while (dir != null && dir != stopAt && dir.isDirectory && dir.list().isNullOrEmpty()) {
            if (!dir.delete()) return
            dir = dir.parentFile
        }
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + FilePathValidator.RESERVED_SUFFIX)
        try {
            tmp.writeBytes(text.toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(
                    tmp.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private companion object {
        const val META_FILE = "project.properties"
        const val SOURCE_DIR = "files"
        val ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
