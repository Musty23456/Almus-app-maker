package com.almus.appcreatorpro.debug

/**
 * Turns the path printed in a build log into a file of the project. A cloud build prints machine paths such as
 * `/home/runner/work/repo/repo/app/src/main/java/com/x/MainActivity.kt`; the project only knows
 * `app/src/main/java/com/x/MainActivity.kt`. The match is by whole trailing path segments, never by guessing: when
 * two project files could both match, the answer is null instead of a wrong file.
 */
class ProjectPathResolver(projectFiles: Collection<String>) {
    private val files: List<String> = projectFiles.map { it.replace('\\', '/').trimStart('/') }.distinct()
    private val fileSet: Set<String> = files.toSet()

    fun resolve(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var path = raw.trim().replace('\\', '/')
        if (path.startsWith("file://")) path = path.removePrefix("file://")
        path = path.substringBefore('?')
        // "C:/x/y" and "/x/y" both become segments that can be compared from the end.
        val normalized = path.trimStart('/')
        if (normalized.isEmpty()) return null
        if (normalized in fileSet) return normalized

        // 1. A project file whose whole path is the tail of the logged path (longest wins).
        val tails = files.filter { normalized.endsWith("/$it") }
        if (tails.isNotEmpty()) {
            val best = tails.maxOf { it.length }
            val top = tails.filter { it.length == best }
            return top.singleOrNull()
        }
        // 2. The logged path is relative and shorter: it is the tail of a project file ("src/main/.../A.kt").
        val heads = files.filter { it.endsWith("/$normalized") }
        if (heads.size == 1) return heads.first()
        if (heads.size > 1) return null

        // 3. Same file name. Only accepted when exactly one project file has it.
        val name = normalized.substringAfterLast('/')
        val byName = files.filter { it.substringAfterLast('/') == name }
        return byName.singleOrNull()
    }
}
