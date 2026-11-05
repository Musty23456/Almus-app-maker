package com.almus.appcreatorpro.domain.transfer

import com.almus.appcreatorpro.domain.transfer.TransferException.Kind

/** Why a file is deliberately not imported. */
enum class IgnoreReason(val label: String) {
    VERSION_CONTROL("version-control and IDE folders"),
    BUILD_OUTPUT("build output folders"),
    DEPENDENCIES("dependency folders (node_modules)"),
    SYSTEM_JUNK("system files"),
    SECRET("local settings and signing keys (left out for safety)"),
    COMPILED_PACKAGE("compiled .apk / .aab packages"),
}

/**
 * Decides which archive entry names are safe and which files are not worth importing. Pure functions.
 *
 * Path traversal ("zip slip"): an entry that is absolute, names a drive, contains a ".." segment or a control
 * character is NOT skipped, it makes the whole archive be rejected, because that is how malicious archives look.
 */
object ZipEntryGuard {

    private val ignoredDirs: Map<String, IgnoreReason> = mapOf(
        ".git" to IgnoreReason.VERSION_CONTROL,
        ".svn" to IgnoreReason.VERSION_CONTROL,
        ".hg" to IgnoreReason.VERSION_CONTROL,
        ".idea" to IgnoreReason.VERSION_CONTROL,
        ".gradle" to IgnoreReason.BUILD_OUTPUT,
        ".cxx" to IgnoreReason.BUILD_OUTPUT,
        ".externalNativeBuild" to IgnoreReason.BUILD_OUTPUT,
        "node_modules" to IgnoreReason.DEPENDENCIES,
        "__MACOSX" to IgnoreReason.SYSTEM_JUNK,
    )

    /** Sub-folders of a "build" folder that only ever hold generated Gradle output. */
    private val gradleOutputDirs = setOf(
        "intermediates", "outputs", "generated", "tmp", "reports", "kotlin", "classes", "test-results", "bundle",
    )

    private val junkFiles = setOf(".DS_Store", "Thumbs.db", "desktop.ini")
    private val secretExtensions = setOf("jks", "keystore")
    private val packageExtensions = setOf("apk", "aab")

    /**
     * Returns the cleaned relative path ('/' separators, no empty or "." segments), or null when the name only
     * refers to the archive root itself. Throws [TransferException] (UNSAFE_PATH) for dangerous names.
     */
    fun normalize(rawName: String): String? {
        if (rawName.any { it.code < 0x20 || it.code == 0x7f }) throw unsafe()
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/")) throw unsafe()
        if (name.length >= 2 && name[1] == ':' && name[0].isLetter()) throw unsafe()
        val segments = ArrayList<String>()
        for (segment in name.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> throw unsafe()
                else -> segments.add(segment)
            }
        }
        if (segments.isEmpty()) return null
        return segments.joinToString("/")
    }

    /** [path] must already be normalized. Null means "import it". */
    fun ignoreReason(path: String): IgnoreReason? {
        val segments = path.split('/')
        val dirs = segments.dropLast(1)
        val file = segments.last()
        for ((index, dir) in dirs.withIndex()) {
            ignoredDirs[dir]?.let { return it }
            if (dir == "build" && index + 1 < dirs.size && dirs[index + 1] in gradleOutputDirs) {
                return IgnoreReason.BUILD_OUTPUT
            }
        }
        if (file in junkFiles || file.startsWith("._") || file.endsWith(".almus-tmp")) return IgnoreReason.SYSTEM_JUNK
        if (file == "local.properties") return IgnoreReason.SECRET
        val extension = file.substringAfterLast('.', "").lowercase()
        if (extension in secretExtensions) return IgnoreReason.SECRET
        if (extension in packageExtensions) return IgnoreReason.COMPILED_PACKAGE
        return null
    }

    private fun unsafe() = TransferException(
        Kind.UNSAFE_PATH,
        "This archive contains a file path that points outside the project folder, so it was not imported.",
    )
}
