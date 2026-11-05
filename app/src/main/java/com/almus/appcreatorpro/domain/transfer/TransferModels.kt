package com.almus.appcreatorpro.domain.transfer

import com.almus.appcreatorpro.domain.model.ProjectType
import java.io.File
import java.io.InputStream

/** Where an import came from. */
enum class ImportSourceKind(val label: String) {
    ZIP_FILE("ZIP file"),
    LOCAL_FOLDER("Folder on this device"),
    GITHUB("GitHub repository"),
    BACKUP("Backup"),
}

/**
 * Safety limits applied while unpacking. They are enforced on the bytes that are really read, never on sizes the
 * archive claims about itself.
 */
data class ImportLimits(
    val maxEntries: Int = 20_000,
    /** All files together. */
    val maxTotalBytes: Long = 500L * 1024 * 1024,
    /** A single file larger than this is skipped (and reported), the import continues. */
    val maxFileBytes: Long = 100L * 1024 * 1024,
    val maxPathLength: Int = 400,
    val maxSegmentLength: Int = 200,
    val maxDepth: Int = 40,
    /** Zip-bomb guard: once more than [ratioCheckAfterBytes] were written, uncompressed/compressed above this aborts. */
    val maxCompressionRatio: Long = 200L,
    val ratioCheckAfterBytes: Long = 64L * 1024 * 1024,
)

/** Progress of any long transfer operation. [totalBytes] is null when the size is unknown (indeterminate bar). */
data class TransferProgress(
    val phase: Phase,
    val doneBytes: Long = 0L,
    val totalBytes: Long? = null,
    val files: Int = 0,
    val currentName: String? = null,
) {
    enum class Phase(val label: String) {
        DOWNLOADING("Downloading"),
        READING("Reading files"),
        CHECKING("Checking project"),
        SAVING("Saving project"),
        WRITING("Writing ZIP"),
    }

    /** 0f..1f, or null when unknown. */
    val fraction: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return (doneBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
}

/** What the user is shown before anything is saved. [problems] block the import; [warnings] do not. */
data class ImportPreview(
    val sourceKind: ImportSourceKind,
    val sourceName: String,
    val suggestedName: String,
    val type: ProjectType?,
    val packageName: String,
    val description: String,
    val editableFiles: Int,
    val preservedFiles: Int,
    val totalBytes: Long,
    val reasons: List<String>,
    val warnings: List<String>,
    val problems: List<String>,
    val preservedExamples: List<String> = emptyList(),
) {
    val canImport: Boolean get() = problems.isEmpty() && type != null
    val totalFiles: Int get() = editableFiles + preservedFiles
}

/** Files unpacked into a staging folder, waiting for the user's confirmation. */
class StagedProject(
    /** Contains `files/` (editable text) and `extra/` (everything else the project needs). */
    val dir: File,
    val preview: ImportPreview,
)

/**
 * A folder (for example one chosen with the Storage Access Framework) that can be walked file by file.
 * No Android types here so the importer can be tested on a plain JVM.
 */
interface TreeSource {
    val rootName: String

    /**
     * Calls [action] for every regular file below the root. [path] is relative and uses '/'. [open] opens a fresh
     * stream that the receiver must close.
     */
    fun forEachFile(action: (path: String, size: Long?, open: () -> InputStream) -> Unit)
}

enum class ProjectSort(val label: String) {
    RECENTLY_UPDATED("Recently updated"),
    RECENTLY_OPENED("Recently opened"),
    NAME_AZ("Name A-Z"),
    NAME_ZA("Name Z-A"),
    NEWEST_CREATED("Newest created"),
    TYPE("Project type"),
}

enum class BackupKind(val label: String) {
    MANUAL("Manual backup"),
    BEFORE_RESTORE("Safety copy before restore"),
}

data class BackupInfo(
    val id: String,
    val projectId: String,
    val projectName: String,
    val type: ProjectType,
    val packageName: String,
    val description: String,
    val createdAtMillis: Long,
    val label: String,
    val kind: BackupKind,
    val fileCount: Int,
    val zipBytes: Long,
)

data class ExportSummary(val fileCount: Int, val bytes: Long, val skipped: Int = 0)
