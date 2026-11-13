package com.almus.appcreatorpro.service.artifacts

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** An APK saved by the app. [file] lives in the app's private storage. */
class StoredApk(val buildId: String, val file: File) {
    val fileName: String get() = file.name

    /** Phase 6: true for an Android App Bundle (.aab); false for an APK. */
    val isBundle: Boolean get() = file.name.endsWith(".aab", ignoreCase = true)
    val sizeBytes: Long get() = file.length()
    val lastModifiedMillis: Long get() = file.lastModified()
}

/** Result of [ApkStore.copyToWithChecksum]. */
class CopyResult(val bytes: Long, val sha256: String)

/**
 * Where downloaded APKs live: `<root>/<buildId>/<name>.apk`, inside the app's private storage (no storage permission
 * needed). The user can share an APK or copy it to a folder of their choice from there.
 * Build ids and file names are validated, so no caller can reach outside [root].
 */
class ApkStore(private val root: File) {

    /** Scratch space for partial downloads. Never contains finished APKs. */
    val tempDir: File = File(root, ".tmp")

    fun dirFor(buildId: String): File {
        require(BUILD_ID.matches(buildId)) { "Invalid build id." }
        return File(root, buildId)
    }

    fun list(buildId: String): List<StoredApk> {
        val dir = dirFor(buildId)
        return dir.listFiles().orEmpty()
            .filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".aab")) && it.length() > 0L }
            .sortedBy { it.name }
            .map { StoredApk(buildId, it) }
    }

    fun find(buildId: String, fileName: String): StoredApk? {
        if (!validFileName(fileName)) return null
        val file = File(dirFor(buildId), fileName)
        return if (file.isFile && file.length() > 0L) StoredApk(buildId, file) else null
    }

    fun delete(buildId: String, fileName: String): Boolean {
        if (!validFileName(fileName)) return false
        return File(dirFor(buildId), fileName).delete()
    }

    /** Build ids that have at least one saved file, newest first. Used by the artifact manager. */
    fun buildIdsWithFiles(): List<String> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && BUILD_ID.matches(it.name) && list(it.name).isNotEmpty() }
            .sortedByDescending { dir -> dir.listFiles().orEmpty().maxOfOrNull { it.lastModified() } ?: 0L }
            .map { it.name }

    fun deleteAll(buildId: String) {
        dirFor(buildId).deleteRecursively()
    }

    /** Removes leftovers of interrupted downloads (call at app start). */
    fun cleanTemp() {
        tempDir.listFiles().orEmpty().forEach { it.delete() }
    }

    /** Streams the APK into [out] (e.g. a file the user picked). Returns the number of bytes copied. Does not close [out]. */
    fun copyTo(apk: StoredApk, out: OutputStream): Long {
        apk.file.inputStream().use { return copyStream(it, out) }
    }

    /**
     * Like [copyTo], and also computes the SHA-256 of exactly the bytes that were read and written. The caller compares it
     * with the checksum it expected, so a file that changed during the copy is noticed.
     */
    fun copyToWithChecksum(apk: StoredApk, out: OutputStream): CopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = apk.file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
                total += n
            }
            out.flush()
            total
        }
        return CopyResult(bytes, Checksums.hex(digest.digest()))
    }

    private fun copyStream(input: InputStream, out: OutputStream): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        out.flush()
        return total
    }

    companion object {
        private val BUILD_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
        private val FILE_NAME = Regex("^[A-Za-z0-9._-]{1,120}$")

        fun validBuildId(id: String): Boolean = BUILD_ID.matches(id)

        fun validFileName(name: String): Boolean = FILE_NAME.matches(name) && !name.contains("..")
    }
}
