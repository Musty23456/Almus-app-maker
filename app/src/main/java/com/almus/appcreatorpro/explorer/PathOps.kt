package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.domain.validation.FilePathValidator

/** Pure path helpers. Every path is relative, '/' separated and checked by [FilePathValidator] before it is used. */
object PathOps {

    /** Cleans what a user typed: trims, turns '\' into '/', drops a leading "./" or "/", collapses "//". */
    fun normalizeInput(raw: String): String {
        var p = raw.trim().replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        while (p.startsWith("./")) p = p.removePrefix("./")
        p = p.trimStart('/')
        return p
    }

    /** Error message for an invalid file path, or null. */
    fun validateFile(path: String): String? = FilePathValidator.validate(path).errorMessage

    /** Error message for an invalid folder path (the same rules as a file path), or null. */
    fun validateFolder(path: String): String? = FilePathValidator.validate(path.trimEnd('/')).errorMessage

    fun parent(path: String): String = if (path.contains('/')) path.substringBeforeLast('/') else ""

    fun join(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    fun isInside(folder: String, path: String): Boolean = folder.isNotEmpty() && path.startsWith("$folder/")

    /** All ancestor folders of [path], outermost first ("a/b/c.kt" -> "a", "a/b"). */
    fun ancestors(path: String): List<String> {
        val parts = path.split('/')
        return (1 until parts.size).map { parts.subList(0, it).joinToString("/") }
    }

    /** Re-roots [path] when the folder [from] moves to [to]; returns null if [path] is not inside [from]. */
    fun moveUnder(from: String, to: String, path: String): String? =
        if (isInside(from, path)) to + "/" + path.removePrefix("$from/") else null

    /**
     * "Foo.kt" -> "Foo copy.kt", then "Foo copy 2.kt", ... The result is a valid, unused path in the same folder,
     * or null when none can be found.
     */
    fun duplicateName(path: String, existing: Set<String>): String? {
        val folder = parent(path)
        val name = path.substringAfterLast('/')
        val ext = when {
            name.endsWith(".gradle.kts") -> ".gradle.kts"
            name.lastIndexOf('.') > 0 -> name.substring(name.lastIndexOf('.'))
            else -> ""
        }
        val base = name.removeSuffix(ext)
        for (n in 1..999) {
            val suffix = if (n == 1) " copy" else " copy $n"
            val room = FilePathValidator.MAX_SEGMENT_LENGTH - suffix.length - ext.length
            if (room < 1) return null
            val candidate = join(folder, base.take(room).trimEnd() + suffix + ext)
            if (candidate !in existing && validateFile(candidate) == null) return candidate
        }
        return null
    }
}
