package com.almus.appcreatorpro.data.transfer

import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.domain.transfer.ImportLimits
import com.almus.appcreatorpro.domain.transfer.ProjectDetector
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.domain.transfer.ZipEntryGuard
import com.almus.appcreatorpro.domain.validation.FilePathValidator
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipException

/** The result of unpacking: two folders inside the staging directory plus statistics. */
class StagedLayout(
    val filesDir: File,
    val extraDir: File,
    val editablePaths: List<String>,
    val preservedPaths: List<String>,
    val totalBytes: Long,
    /** Reason label -> number of skipped files. */
    val skipped: Map<String, Int>,
)

/**
 * Safely unpacks files into a staging directory. It is fed one file at a time ([addFile]), by a ZIP reader or a
 * folder walker, and finally sorted into `files/` and `extra/` by [finish].
 *
 * Security: every name goes through [ZipEntryGuard] (traversal, absolute paths, drive letters, control characters
 * abort the import), the destination is checked once more against the staging folder's canonical path, only
 * regular files are ever created (a "symlink" entry in a ZIP becomes a harmless file), and all size limits count
 * the bytes that were really read.
 */
class ProjectStager(
    private val stagingDir: File,
    private val limits: ImportLimits = ImportLimits(),
    private val checkCancelled: () -> Unit = {},
    /** Compressed bytes consumed so far (for the zip-bomb ratio check); 0 when unknown. */
    private val compressedBytesRead: () -> Long = { 0L },
    private val progressTotalBytes: Long? = null,
    private val onProgress: (TransferProgress) -> Unit = {},
) {
    private val rawDir = File(stagingDir, RAW)
    private var entryCount = 0
    private var fileCount = 0
    private var totalBytes = 0L
    private val skipped = LinkedHashMap<String, Int>()

    init {
        if (!rawDir.isDirectory && !rawDir.mkdirs()) {
            throw TransferException(Kind.STORAGE, "Could not prepare temporary storage on this phone.")
        }
    }

    /** Directory entries only need to be safe; folders are created on demand by the files inside them. */
    fun addDirectory(rawName: String) {
        checkCancelled()
        ZipEntryGuard.normalize(rawName)
    }

    /**
     * @param openInput called only when the file will really be read (ignored files are never opened).
     * @param closeInput true when this stager owns the stream (folder import); false for a shared ZIP stream.
     */
    fun addFile(rawName: String, declaredSize: Long?, closeInput: Boolean, openInput: () -> InputStream) {
        checkCancelled()
        val path = ZipEntryGuard.normalize(rawName) ?: return
        val reason = ZipEntryGuard.ignoreReason(path)
        if (reason != null) {
            skip(reason.label)
            return
        }
        entryCount++
        if (entryCount > limits.maxEntries) {
            throw TransferException(Kind.TOO_MANY_FILES, "This project has more than ${limits.maxEntries} files, which is more than the app can import.")
        }
        val segments = path.split('/')
        if (path.length > limits.maxPathLength || segments.size > limits.maxDepth || segments.any { it.length > limits.maxSegmentLength }) {
            skip(LABEL_PATH_TOO_LONG)
            return
        }
        if (declaredSize != null && declaredSize > limits.maxFileBytes) {
            skip(LABEL_FILE_TOO_BIG)
            return
        }
        val dest = File(rawDir, path)
        verifyInsideStaging(dest)
        if (dest.exists()) {
            skip(LABEL_DUPLICATE)
            return
        }
        if (!prepareParent(dest)) {
            skip(LABEL_NAME_CONFLICT)
            return
        }

        val input = try {
            openInput()
        } catch (e: IOException) {
            throw TransferException(Kind.READ_FAILED, "Could not read \"${segments.last()}\".", e)
        }
        var written = 0L
        var tooBig = false
        try {
            FileOutputStream(dest).use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    checkCancelled()
                    val n = try {
                        input.read(buffer)
                    } catch (e: ZipException) {
                        throw TransferException(Kind.CORRUPT_ARCHIVE, "The ZIP file is damaged or uses a feature that is not supported (for example a password).", e)
                    } catch (e: IOException) {
                        throw TransferException(Kind.READ_FAILED, "Could not read \"${segments.last()}\".", e)
                    }
                    if (n < 0) break
                    written += n
                    if (written > limits.maxFileBytes) {
                        tooBig = true
                        break
                    }
                    val soFar = totalBytes + written
                    if (soFar > limits.maxTotalBytes) {
                        throw TransferException(Kind.TOO_LARGE, "The project is larger than ${limits.maxTotalBytes / (1024 * 1024)} MB and was not imported.")
                    }
                    val compressed = compressedBytesRead()
                    if (compressed > 0L && soFar > limits.ratioCheckAfterBytes && soFar / compressed > limits.maxCompressionRatio) {
                        throw TransferException(Kind.TOO_LARGE, "This archive expands to an unreasonable size (possible ZIP bomb) and was not imported.")
                    }
                    try {
                        out.write(buffer, 0, n)
                    } catch (e: IOException) {
                        throw TransferException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
                    }
                }
            }
        } finally {
            if (closeInput) {
                try {
                    input.close()
                } catch (_: IOException) {
                    // nothing useful to do
                }
            }
        }
        if (tooBig) {
            dest.delete()
            skip(LABEL_FILE_TOO_BIG)
            return
        }
        totalBytes += written
        fileCount++
        onProgress(
            TransferProgress(
                phase = TransferProgress.Phase.READING,
                doneBytes = compressedBytesRead().takeIf { it > 0L } ?: totalBytes,
                totalBytes = progressTotalBytes,
                files = fileCount,
                currentName = segments.last(),
            ),
        )
    }

    /**
     * Sorts everything into `files/` (editable text) and `extra/` (the rest). If the archive has exactly one top
     * level folder and no files next to it, that wrapper folder is removed (always for GitHub archives; otherwise
     * only when the folder holds the project's root files).
     */
    fun finish(stripSingleRoot: Boolean): StagedLayout {
        checkCancelled()
        val rawFiles = rawDir.walkTopDown()
            .filter { it.isFile }
            .map { it to it.relativeTo(rawDir).invariantSeparatorsPath }
            .toList()
        if (rawFiles.isEmpty()) {
            val why = if (skipped.isEmpty()) "" else " (${skipped.values.sum()} files were skipped, for example build folders or signing keys)"
            throw TransferException(Kind.EMPTY, "There are no importable files in this archive$why.")
        }
        val prefix = rootPrefixToStrip(rawFiles.map { it.second }, stripSingleRoot)
        val filesDir = File(stagingDir, FILES)
        val extraDir = File(stagingDir, EXTRA)
        val editable = ArrayList<String>()
        val preserved = ArrayList<String>()
        try {
            for ((file, rel) in rawFiles) {
                checkCancelled()
                val path = if (prefix.isEmpty()) rel else rel.removePrefix(prefix)
                val isEditable = isEditableText(file, path)
                val dest = File(if (isEditable) filesDir else extraDir, path)
                dest.parentFile?.mkdirs()
                Files.move(file.toPath(), dest.toPath())
                if (isEditable) editable += path else preserved += path
            }
        } catch (e: IOException) {
            throw TransferException(Kind.STORAGE, "Could not organize the project files. Is the phone's storage full?", e)
        }
        rawDir.deleteRecursively()
        if (!filesDir.isDirectory) filesDir.mkdirs()
        return StagedLayout(filesDir, extraDir, editable.sorted(), preserved.sorted(), totalBytes, skipped.toMap())
    }

    // ---------------------------------------------------------------------------------------------- internals

    private fun skip(label: String) {
        skipped[label] = (skipped[label] ?: 0) + 1
    }

    private fun verifyInsideStaging(dest: File) {
        val root = rawDir.canonicalFile.toPath()
        val target = dest.canonicalFile.toPath()
        if (!target.startsWith(root) || target == root) {
            throw TransferException(Kind.UNSAFE_PATH, "This archive contains a file path that points outside the project folder, so it was not imported.")
        }
    }

    /** False when a parent "folder" already exists as a file (or the folder cannot be created). */
    private fun prepareParent(dest: File): Boolean {
        var parent = dest.parentFile
        while (parent != null && parent != rawDir) {
            if (parent.isFile) return false
            parent = parent.parentFile
        }
        val dir = dest.parentFile ?: return false
        if (dest.isDirectory) return false
        return dir.isDirectory || dir.mkdirs()
    }

    private fun rootPrefixToStrip(paths: List<String>, force: Boolean): String {
        if (paths.any { !it.contains('/') }) return ""
        val tops = paths.map { it.substringBefore('/') }.toSet()
        if (tops.size != 1) return ""
        val top = tops.first()
        if (force) return "$top/"
        val inner = paths.map { it.removePrefix("$top/") }
        return if (inner.any { it in ProjectDetector.ROOT_MARKERS }) "$top/" else ""
    }

    private fun isEditableText(file: File, path: String): Boolean {
        if (!FilePathValidator.validate(path).isValid) return false
        if (file.length() > SourceFileRepository.MAX_FILE_BYTES) return false
        return looksLikeUtf8Text(file)
    }

    companion object {
        const val RAW = "raw"
        const val FILES = "files"
        const val EXTRA = "extra"
        const val LABEL_PATH_TOO_LONG = "paths that are too long or nested too deeply"
        const val LABEL_FILE_TOO_BIG = "single files larger than the size limit"
        const val LABEL_DUPLICATE = "duplicate entries"
        const val LABEL_NAME_CONFLICT = "names that clash with a folder"
        private const val BUFFER = 32 * 1024

        /** UTF-8 text without NUL bytes: what the editor can open. */
        fun looksLikeUtf8Text(file: File): Boolean {
            val bytes = try {
                file.readBytes()
            } catch (e: IOException) {
                return false
            }
            return looksLikeUtf8Text(bytes)
        }

        fun looksLikeUtf8Text(bytes: ByteArray): Boolean {
            if (bytes.any { it == 0.toByte() }) return false
            return try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                true
            } catch (e: CharacterCodingException) {
                false
            }
        }
    }
}
