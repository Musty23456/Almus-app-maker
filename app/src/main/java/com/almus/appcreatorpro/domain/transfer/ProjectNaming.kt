package com.almus.appcreatorpro.domain.transfer

import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Turns arbitrary text (file name, repository name, folder name) into a name [ProjectNameValidator] accepts. */
object ProjectNaming {
    private val invalidChars = Regex("[^A-Za-z0-9 _.\\-]+")
    private val whitespace = Regex("\\s+")

    fun stripExtension(fileName: String): String {
        val lower = fileName.lowercase()
        return if (lower.endsWith(".zip")) fileName.dropLast(4) else fileName
    }

    fun sanitize(raw: String?): String {
        var name = raw.orEmpty().replace(invalidChars, " ").replace(whitespace, " ").trim()
        name = name.trimStart { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9') }
        if (name.length > ProjectNameValidator.MAX_LENGTH) name = name.take(ProjectNameValidator.MAX_LENGTH).trimEnd()
        if (name.isEmpty()) name = "Imported project"
        if (name.length < ProjectNameValidator.MIN_LENGTH) name = "$name project"
        return name
    }

    /** [base] if free, otherwise "base 2", "base 3", ... (always within the length limit). */
    fun unique(base: String, existing: Collection<String>): String {
        fun taken(candidate: String) = existing.any { it.trim().equals(candidate, ignoreCase = true) }
        if (!taken(base)) return base
        var n = 2
        while (n < 10_000) {
            val suffix = " $n"
            val candidate = base.take(ProjectNameValidator.MAX_LENGTH - suffix.length).trimEnd() + suffix
            if (!taken(candidate)) return candidate
            n++
        }
        return base
    }

    /** "My App" at 2026-03-05 14:07 -> "My-App-20260305-1407.zip". */
    fun exportFileName(projectName: String, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val slug = projectName.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "project" }.take(40)
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US).format(Instant.ofEpochMilli(nowMillis).atZone(zone))
        return "$slug-$stamp.zip"
    }
}
