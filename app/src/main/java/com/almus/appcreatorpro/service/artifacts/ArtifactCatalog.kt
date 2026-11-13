package com.almus.appcreatorpro.service.artifacts

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/** One saved APK/AAB as the manager lists it. [report] is null until the file has been checked. */
data class ArtifactEntry(
    val buildId: String,
    val file: StoredApk,
    val projectName: String,
    val context: BuildContext?,
    val sizeBytes: Long,
    val savedAtMillis: Long,
    val report: ArtifactReport? = null,
) {
    val key: String get() = "$buildId/${file.fileName}"
    val fileName: String get() = file.fileName
    val type: ArtifactType get() = if (file.isBundle) ArtifactType.AAB else ArtifactType.APK

    /** Best guess of debug/release for sorting and filtering before (or without) a full check. */
    val kindHint: BuildKind
        get() = report?.kind
            ?: context?.intent?.kind?.takeIf { it != BuildKind.UNKNOWN }
            ?: BuildKind.fromFileName(fileName)

    /** Why this file must not be shared or exported right now, or null when it may be. */
    val shareBlockedReason: String?
        get() {
            val r = report ?: return "The file is still being checked. Try again in a moment."
            return when (r.integrity.state) {
                IntegrityState.MODIFIED -> "This file changed after it was saved, so it is not shared. Download it again."
                IntegrityState.CORRUPT -> "This file is damaged, so it is not shared. Download it again."
                IntegrityState.UNREADABLE -> "This file could not be read, so it is not shared."
                else -> null
            }
        }
}

sealed interface ExportOutcome {
    data class Saved(val bytes: Long, val sha256: String) : ExportOutcome

    /** The file changed while it was being copied; the copy must not be trusted. */
    data class Changed(val bytes: Long) : ExportOutcome
}

data class DeleteSummary(val deleted: Int, val failed: Int, val freedBytes: Long)

/**
 * The artifact manager's engine: lists what is saved, checks it, exports it and deletes it.
 * Blocking (file work); callers run it off the main thread.
 */
class ArtifactCatalog(
    private val store: ApkStore,
    private val registry: ArtifactRegistry,
    private val assessor: ArtifactAssessor,
) {
    private class Cached(val size: Long, val modified: Long, val report: ArtifactReport)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Everything saved on the phone, newest first. Reports already known are attached; the rest are null. */
    fun list(contexts: Map<String, BuildContext>): List<ArtifactEntry> {
        val out = ArrayList<ArtifactEntry>()
        for (id in store.buildIdsWithFiles()) {
            val context = contexts[id]
            for (stored in store.list(id)) {
                val size = stored.sizeBytes
                val modified = stored.lastModifiedMillis
                val key = "$id/${stored.fileName}"
                val known = cache[key]?.takeIf { it.size == size && it.modified == modified }?.report
                out += ArtifactEntry(id, stored, context?.projectName ?: "Unknown project", context, size, modified, known)
            }
        }
        return out.sortedByDescending { it.savedAtMillis }
    }

    /** Checks one file (or returns the earlier result when the file has not changed and [force] is false). */
    fun assess(entry: ArtifactEntry, force: Boolean = false): ArtifactEntry {
        val file = entry.file.file
        val size = file.length()
        val modified = file.lastModified()
        val cached = cache[entry.key]
        if (!force && cached != null && cached.size == size && cached.modified == modified) {
            return entry.copy(report = cached.report, sizeBytes = size, savedAtMillis = modified)
        }
        val report = assessor.assess(entry.buildId, file, entry.context)
        cache[entry.key] = Cached(size, modified, report)
        return entry.copy(report = report, sizeBytes = size, savedAtMillis = modified)
    }

    fun forgetAll() {
        cache.clear()
    }

    /** Deletes the file and its recorded checksum. True when the file is gone. */
    fun delete(entry: ArtifactEntry): Boolean {
        store.delete(entry.buildId, entry.file.fileName)
        val gone = !entry.file.file.exists()
        if (gone) {
            registry.remove(entry.buildId, entry.file.fileName)
            cache.remove(entry.key)
            // An empty build folder is removed too (delete() on a folder only succeeds when it is empty).
            store.dirFor(entry.buildId).delete()
        }
        return gone
    }

    fun deleteMany(entries: List<ArtifactEntry>): DeleteSummary {
        var deleted = 0
        var failed = 0
        var freed = 0L
        for (e in entries) {
            val size = e.file.sizeBytes
            if (delete(e)) {
                deleted++
                freed += size
            } else {
                failed++
            }
        }
        return DeleteSummary(deleted, failed, freed)
    }

    /**
     * Copies the file into [out] and compares the checksum of the copied bytes with [expectedSha256] (the one found by
     * the last check). A mismatch means the file changed during the copy.
     */
    @Throws(IOException::class)
    fun export(entry: ArtifactEntry, expectedSha256: String?, out: OutputStream): ExportOutcome {
        val result = store.copyToWithChecksum(entry.file, out)
        if (expectedSha256 != null && !result.sha256.equals(expectedSha256, ignoreCase = true)) {
            return ExportOutcome.Changed(result.bytes)
        }
        return ExportOutcome.Saved(result.bytes, result.sha256)
    }

    /** Records the checksum of a file the app has just downloaded, so later checks can detect changes. */
    fun recordDownloaded(buildId: String, file: File, now: Long) {
        recordDownload(registry, buildId, file, now)
    }

    companion object {
        /** Used by the download manager right after a verified download. Failure to record is not an error. */
        fun recordDownload(registry: ArtifactRegistry, buildId: String, file: File, now: Long) {
            try {
                registry.put(buildId, file.name, ArtifactRecord(Checksums.sha256(file), file.length(), now, "download"))
            } catch (e: IOException) {
                // The first check will record a checksum instead.
            }
        }
    }
}
