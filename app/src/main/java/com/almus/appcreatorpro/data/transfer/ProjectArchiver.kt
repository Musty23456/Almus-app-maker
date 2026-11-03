package com.almus.appcreatorpro.data.transfer

import com.almus.appcreatorpro.domain.repository.ProjectTransferRepository
import com.almus.appcreatorpro.domain.transfer.ExportSummary
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.domain.validation.FilePathValidator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.nio.file.NoSuchFileException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a whole project (editable files AND preserved extra files) as a ZIP with the same folder structure the
 * project has. The caller owns [OutputStream] and closes it; on failure the caller should delete the partial output.
 */
class ProjectArchiver(
    private val projects: ProjectTransferRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun writeZip(
        projectId: String,
        out: OutputStream,
        onProgress: (TransferProgress) -> Unit = {},
    ): ExportSummary {
        val roots = try {
            projects.contentRoots(projectId)
        } catch (e: com.almus.appcreatorpro.domain.error.StorageException) {
            throw TransferException(Kind.NOT_FOUND, e.message ?: "This project no longer exists.", e)
        }
        return withContext(ioDispatcher) {
            val ctx = currentCoroutineContext()
            val files = collect(roots.sourceDir) + collect(roots.extraDir)
            val sorted = files.sortedBy { it.first }
            if (sorted.isEmpty()) throw TransferException(Kind.EMPTY, "This project has no files to export.")
            val totalBytes = sorted.sumOf { it.second.length() }
            var doneBytes = 0L
            var written = 0
            var skipped = 0
            onProgress(TransferProgress(TransferProgress.Phase.WRITING, 0L, totalBytes, 0, null))
            try {
                val zip = ZipOutputStream(out)
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                val buffer = ByteArray(BUFFER)
                for ((path, file) in sorted) {
                    ctx.ensureActive()
                    val input = try {
                        file.inputStream()
                    } catch (e: FileNotFoundException) {
                        skipped++ // deleted while exporting
                        continue
                    } catch (e: NoSuchFileException) {
                        skipped++
                        continue
                    }
                    input.use { stream ->
                        val entry = ZipEntry(path)
                        entry.time = file.lastModified()
                        zip.putNextEntry(entry)
                        while (true) {
                            ctx.ensureActive()
                            val n = stream.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            doneBytes += n
                            onProgress(TransferProgress(TransferProgress.Phase.WRITING, doneBytes, totalBytes, written, file.name))
                        }
                        zip.closeEntry()
                    }
                    written++
                }
                zip.finish()
                zip.flush()
            } catch (e: IOException) {
                throw TransferException(Kind.STORAGE, "Could not write the ZIP file. Is the destination full or no longer available?", e)
            }
            if (written == 0) throw TransferException(Kind.EMPTY, "This project has no files to export.")
            onProgress(TransferProgress(TransferProgress.Phase.WRITING, totalBytes, totalBytes, written, null))
            ExportSummary(fileCount = written, bytes = doneBytes, skipped = skipped)
        }
    }

    /** (relative path, file) for every regular file below [root], skipping leftover temp files. */
    private fun collect(root: File): List<Pair<String, File>> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(FilePathValidator.RESERVED_SUFFIX) }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .toList()
    }

    private companion object {
        const val BUFFER = 32 * 1024
    }
}
