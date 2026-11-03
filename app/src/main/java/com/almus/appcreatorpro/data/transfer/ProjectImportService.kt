package com.almus.appcreatorpro.data.transfer

import com.almus.appcreatorpro.domain.github.CloudBuildValidator
import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.ProjectRepository
import com.almus.appcreatorpro.domain.repository.ProjectTransferRepository
import com.almus.appcreatorpro.domain.transfer.ImportLimits
import com.almus.appcreatorpro.domain.transfer.ImportPreview
import com.almus.appcreatorpro.domain.transfer.ImportSourceKind
import com.almus.appcreatorpro.domain.transfer.ProjectDetector
import com.almus.appcreatorpro.domain.transfer.ProjectNaming
import com.almus.appcreatorpro.domain.transfer.StagedProject
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.domain.transfer.TreeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Import in two steps so nothing is saved before the user has seen what was found:
 *
 * 1. `stageZip` / `stageZipFile` / `stageTree` unpack into a private staging folder and return a [StagedProject]
 *    with an [ImportPreview] (detected type, package, counts, warnings, problems).
 * 2. [commit] moves the staged files into a new project. [discard] throws the staging folder away.
 *
 * All disk work runs on [ioDispatcher], so large archives never block the UI thread. Staging lives outside the
 * projects folder, so a crash can never leave a half-imported project in the project list.
 */
class ProjectImportService(
    private val stagingRoot: File,
    private val projects: ProjectRepository,
    private val transfer: ProjectTransferRepository,
    private val limits: ImportLimits = ImportLimits(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /** Removes leftovers of imports that were interrupted (process killed). Call once at app start. */
    fun cleanStaleStaging() {
        try {
            stagingRoot.listFiles().orEmpty().forEach { it.deleteRecursively() }
        } catch (_: SecurityException) {
            // best effort
        }
    }

    /** A temp file (for example a downloaded repository archive) inside the staging area. */
    fun newTempFile(prefix: String, suffix: String): File {
        val dir = File(stagingRoot, "downloads")
        if (!dir.isDirectory && !dir.mkdirs()) throw TransferException(Kind.STORAGE, "Could not prepare temporary storage on this phone.")
        return File(dir, prefix + idGenerator().filter { it.isLetterOrDigit() }.take(16) + suffix)
    }

    suspend fun stageZipFile(
        file: File,
        sourceName: String?,
        kind: ImportSourceKind,
        stripSingleRoot: Boolean = false,
        originNote: String = "",
        onProgress: (TransferProgress) -> Unit = {},
    ): StagedProject {
        val input = try {
            file.inputStream()
        } catch (e: IOException) {
            throw TransferException(Kind.READ_FAILED, "Could not read the downloaded file.", e)
        }
        return input.use { stageZip(it, file.length(), sourceName, kind, stripSingleRoot, originNote, onProgress) }
    }

    suspend fun stageZip(
        input: InputStream,
        sizeHint: Long?,
        sourceName: String?,
        kind: ImportSourceKind,
        stripSingleRoot: Boolean = false,
        originNote: String = "",
        onProgress: (TransferProgress) -> Unit = {},
    ): StagedProject = withContext(ioDispatcher) {
        val ctx = currentCoroutineContext()
        val stagingDir = newStagingDir()
        try {
            val buffered = BufferedInputStream(input, 64 * 1024)
            requireZipMagic(buffered)
            val counting = CountingInputStream(buffered)
            onProgress(TransferProgress(TransferProgress.Phase.READING, 0L, sizeHint))
            val stager = ProjectStager(
                stagingDir = stagingDir,
                limits = limits,
                checkCancelled = { ctx.ensureActive() },
                compressedBytesRead = { counting.count },
                progressTotalBytes = sizeHint?.takeIf { it > 0L },
                onProgress = onProgress,
            )
            // Closing the ZipInputStream also closes [input]; that is part of this function's contract.
            ZipInputStream(counting).use { zip ->
                while (true) {
                    val entry = nextEntry(zip) ?: break
                    if (entry.isDirectory) {
                        stager.addDirectory(entry.name)
                    } else {
                        stager.addFile(entry.name, entry.size.takeIf { it >= 0L }, closeInput = false) { zip }
                    }
                }
            }
            onProgress(TransferProgress(TransferProgress.Phase.CHECKING))
            val layout = stager.finish(stripSingleRoot)
            StagedProject(stagingDir, buildPreview(layout, sourceName, kind, originNote))
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw translate(e)
        }
    }

    suspend fun stageTree(
        tree: TreeSource,
        originNote: String = "",
        onProgress: (TransferProgress) -> Unit = {},
    ): StagedProject = withContext(ioDispatcher) {
        val ctx = currentCoroutineContext()
        val stagingDir = newStagingDir()
        try {
            onProgress(TransferProgress(TransferProgress.Phase.READING))
            val stager = ProjectStager(
                stagingDir = stagingDir,
                limits = limits,
                checkCancelled = { ctx.ensureActive() },
                onProgress = onProgress,
            )
            tree.forEachFile { path, size, open ->
                stager.addFile(path, size, closeInput = true, openInput = open)
            }
            onProgress(TransferProgress(TransferProgress.Phase.CHECKING))
            val layout = stager.finish(stripSingleRoot = false)
            StagedProject(stagingDir, buildPreview(layout, tree.rootName, ImportSourceKind.LOCAL_FOLDER, originNote))
        } catch (e: Throwable) {
            stagingDir.deleteRecursively()
            throw translate(e)
        }
    }

    /** Creates the project from what the preview found. Throws ValidationException (staging kept) for a bad name. */
    suspend fun commit(staged: StagedProject, name: String): Project {
        val preview = staged.preview
        val type = preview.type
        if (!preview.canImport || type == null) {
            throw TransferException(Kind.UNSUPPORTED_PROJECT, preview.problems.firstOrNull() ?: "This project cannot be imported.")
        }
        return commitAs(staged, name, preview.packageName, preview.description, type)
    }

    /** Like [commit] with explicit metadata (used when restoring a backup, whose metadata is already known). */
    suspend fun commitAs(staged: StagedProject, name: String, packageName: String, description: String, type: ProjectType): Project {
        val project = try {
            transfer.importStaged(staged.dir, name, packageName, description, type)
        } catch (e: CancellationException) {
            throw e
        } catch (e: com.almus.appcreatorpro.domain.error.StorageException) {
            throw TransferException(Kind.STORAGE, e.message ?: "The project could not be saved.", e)
        }
        discard(staged)
        return project
    }

    suspend fun discard(staged: StagedProject) {
        withContext(ioDispatcher) { staged.dir.deleteRecursively() }
    }

    // ------------------------------------------------------------------------------------------ internals

    private fun newStagingDir(): File {
        val dir = File(stagingRoot, "import-" + idGenerator().filter { it.isLetterOrDigit() }.take(16))
        if (!dir.mkdirs()) throw TransferException(Kind.STORAGE, "Could not prepare temporary storage on this phone.")
        return dir
    }

    private fun requireZipMagic(input: BufferedInputStream) {
        input.mark(8)
        val head = ByteArray(4)
        var read = 0
        try {
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            input.reset()
        } catch (e: IOException) {
            throw TransferException(Kind.READ_FAILED, "Could not read the selected file.", e)
        }
        val isZip = read == 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            (head[2] == 0x03.toByte() || head[2] == 0x05.toByte() || head[2] == 0x07.toByte())
        if (!isZip) throw TransferException(Kind.NOT_A_ZIP, "This file is not a ZIP archive.")
    }

    private fun nextEntry(zip: ZipInputStream): ZipEntry? = try {
        zip.nextEntry
    } catch (e: ZipException) {
        throw TransferException(Kind.CORRUPT_ARCHIVE, "The ZIP file is damaged or uses a feature that is not supported (for example a password).", e)
    } catch (e: IOException) {
        throw TransferException(Kind.READ_FAILED, "Could not read the selected file.", e)
    }

    private suspend fun buildPreview(layout: StagedLayout, sourceName: String?, kind: ImportSourceKind, originNote: String): ImportPreview {
        val allPaths = layout.editablePaths + layout.preservedPaths
        val detection = ProjectDetector.detect(allPaths) { path ->
            val file = File(layout.filesDir, path)
            if (file.isFile) {
                try {
                    file.readText(Charsets.UTF_8)
                } catch (e: IOException) {
                    null
                }
            } else {
                null
            }
        }
        val cleanedSource = sourceName?.let { ProjectNaming.stripExtension(it) }
        val rawName = when (kind) {
            ImportSourceKind.ZIP_FILE -> detection.nameHint ?: cleanedSource
            else -> cleanedSource ?: detection.nameHint
        }
        val existingNames = projects.getProjects().map { it.name }
        val suggestedName = ProjectNaming.unique(ProjectNaming.sanitize(rawName), existingNames)

        val warnings = ArrayList<String>()
        for ((label, count) in layout.skipped) {
            warnings += "$count item(s) were left out: $label."
        }
        if (layout.preservedPaths.isNotEmpty()) {
            warnings += "${layout.preservedPaths.size} file(s) are binary, very large or have names the editor cannot handle. " +
                "They are kept in the project and included in exports and backups, but they are not shown in the editor " +
                "and cloud builds do not upload them."
        }
        if (layout.editablePaths.size > CloudBuildValidator.MAX_FILES && detection.type != ProjectType.WEB) {
            warnings += "Cloud builds accept at most ${CloudBuildValidator.MAX_FILES} files; this project has ${layout.editablePaths.size} editable files."
        }
        warnings += detection.warnings

        return ImportPreview(
            sourceKind = kind,
            sourceName = cleanedSource ?: kind.label,
            suggestedName = suggestedName,
            type = detection.type,
            packageName = detection.packageName,
            description = originNote,
            editableFiles = layout.editablePaths.size,
            preservedFiles = layout.preservedPaths.size,
            totalBytes = layout.totalBytes,
            reasons = detection.reasons,
            warnings = warnings,
            problems = listOfNotNull(detection.problem),
            preservedExamples = layout.preservedPaths.take(5),
        )
    }

    private fun translate(e: Throwable): Throwable = when (e) {
        is CancellationException -> e
        is TransferException -> e
        is SecurityException -> TransferException(Kind.PERMISSION, "Android did not allow access to the selected file or folder. Please choose it again.", e)
        is IOException -> TransferException(Kind.STORAGE, "Could not read or write files. Is the phone's storage full?", e)
        is Exception -> TransferException(Kind.STORAGE, "The import failed unexpectedly. Nothing was imported.", e)
        else -> e
    }
}
