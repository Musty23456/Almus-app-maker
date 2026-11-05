package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.Severity
import com.almus.appcreatorpro.debug.StaticDiagnostics
import com.almus.appcreatorpro.domain.validation.FilePathValidator
import com.almus.appcreatorpro.domain.validation.ValidationResult

data class FileProblem(val path: String, val reason: String)

/** Result of checking generated files. [accepted] may be applied; [rejected] must not be; [warnings] are shown to the user. */
data class ValidationReport(
    val accepted: List<GeneratedFile>,
    val rejected: List<FileProblem>,
    val warnings: List<String>,
    /** Problems the Phase 8 code checks found in the accepted files (errors only), e.g. a malformed XML file. */
    val codeCheckErrors: List<FileProblem>,
) {
    val hasBlockingProblems: Boolean get() = rejected.isNotEmpty() || accepted.isEmpty()
}

/**
 * Nothing an AI produced is written into a project before it passes here: safe relative paths, no binaries or tool
 * folders, no credential files, no secrets in the text, sensible size, no duplicate or conflicting paths.
 */
object GeneratedFileValidator {
    const val MAX_FILES = 80
    const val MAX_FILE_BYTES = 512 * 1024
    const val MAX_TOTAL_BYTES = 4 * 1024 * 1024

    private val forbiddenSegments = setOf(".git", ".gradle", ".idea", ".github", ".vscode", "node_modules", "__MACOSX")
    private val binaryExtensions = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "jar", "aar", "apk", "aab", "so", "dex", "class", "zip", "gz", "tar", "7z",
        "pdf", "ttf", "otf", "mp3", "mp4", "wav", "ogg", "keystore", "jks",
    )
    private val forbiddenNames = setOf("gradlew", "gradlew.bat")

    /** Null = the path is fine; otherwise the reason it is refused. */
    fun checkPath(path: String): String? {
        if (path.contains('\\')) return "Use / in paths, not \\."
        if (path.startsWith("./")) return "Paths must not start with ./"
        when (val v = FilePathValidator.validate(path)) {
            is ValidationResult.Invalid -> return v.message
            ValidationResult.Valid -> Unit
        }
        val segments = path.split('/')
        segments.firstOrNull { it in forbiddenSegments }?.let { return "Files inside \"$it\" are not generated." }
        if (path.startsWith("build/") || path.contains("/build/outputs/") || path.contains("/build/intermediates/")) return "Build output is not generated."
        if (path.startsWith("gradle/wrapper/")) return "Gradle wrapper files are not generated: the cloud build installs Gradle itself."
        val name = segments.last()
        if (name in forbiddenNames) return "Gradle wrapper scripts are not generated."
        if (SecretScanner.isCredentialFile(path)) return "Credential and key files are never generated."
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in binaryExtensions) return "Binary files cannot be generated (text files only)."
        return null
    }

    fun validate(files: List<GeneratedFile>): ValidationReport {
        val rejected = ArrayList<FileProblem>()
        val warnings = ArrayList<String>()
        val accepted = ArrayList<GeneratedFile>()
        if (files.size > MAX_FILES) {
            return ValidationReport(emptyList(), listOf(FileProblem("", "The answer contains ${files.size} files; at most $MAX_FILES are accepted.")), emptyList(), emptyList())
        }
        val seen = HashMap<String, String>() // lower-case path -> original
        var total = 0
        for (f in files) {
            val path = f.path
            val problem: String? = when {
                checkPath(path) != null -> checkPath(path)
                f.content.isBlank() -> "The file is empty."
                f.content.indexOf('\u0000') >= 0 -> "The file contains binary data."
                else -> null
            }
            if (problem != null) { rejected += FileProblem(path, problem); continue }
            val bytes = f.content.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_FILE_BYTES) { rejected += FileProblem(path, "The file is too large (${bytes / 1024} KB; limit ${MAX_FILE_BYTES / 1024} KB)."); continue }
            if (total + bytes > MAX_TOTAL_BYTES) { rejected += FileProblem(path, "The project would exceed ${MAX_TOTAL_BYTES / (1024 * 1024)} MB."); continue }
            val key = path.lowercase()
            val earlier = seen[key]
            if (earlier != null) { rejected += FileProblem(path, "Duplicate of \"$earlier\"."); continue }
            if (SecretScanner.containsSecret(f.content)) { rejected += FileProblem(path, "The file contains what looks like a secret (key, token or password). Secrets are never written by the AI."); continue }
            seen[key] = path
            total += bytes
            accepted += f
        }
        // A path cannot be both a file and a folder.
        val paths = accepted.map { it.path }.toSet()
        val conflicting = accepted.filter { a -> paths.any { other -> other.startsWith(a.path + "/") } }
        for (c in conflicting) rejected += FileProblem(c.path, "This path is also used as a folder by another file.")
        val finalFiles = accepted.filter { a -> conflicting.none { it.path == a.path } }

        for (f in finalFiles) {
            if (placeholder.containsMatchIn(f.content)) warnings += "\"${f.path}\" may be incomplete (it contains a placeholder such as \"...\" or \"rest of the file\")."
            packageMismatch(f)?.let { warnings += it }
        }
        warnings += projectLevelWarnings(finalFiles.map { it.path }.toSet())

        val checks = try {
            StaticDiagnostics().analyze(finalFiles.associate { it.path to it.content }).issues
                .filter { it.severity == Severity.ERROR }
                .map { FileProblem(it.projectPath ?: it.file.orEmpty(), it.message + (it.line?.let { l -> " (line $l)" } ?: "")) }
                .take(20)
        } catch (e: Exception) { emptyList() }
        return ValidationReport(finalFiles, rejected, warnings, checks)
    }

    private val placeholder = Regex("(?m)^\\s*(?://|#|<!--)?\\s*\\.\\.\\.\\s*(?:-->)?\\s*$|rest of (?:the )?(?:file|code|class)|existing code here", RegexOption.IGNORE_CASE)
    private val packageLine = Regex("(?m)^\\s*package\\s+([A-Za-z_][\\w.]*)")

    /** A Kotlin/Java file whose package line does not match its folder fails to build or to be found. */
    private fun packageMismatch(f: GeneratedFile): String? {
        val m = Regex("^(?:.*/)?src/(?:main|test|androidTest)/(?:java|kotlin)/(.+)/[^/]+\\.(?:kt|java)$").find(f.path) ?: return null
        val expected = m.groupValues[1].replace('/', '.')
        val actual = packageLine.find(f.content)?.groupValues?.get(1) ?: return "\"${f.path}\" has no package line (expected package $expected)."
        return if (actual == expected) null else "\"${f.path}\" declares package $actual but its folder is $expected."
    }

    private fun projectLevelWarnings(paths: Set<String>): List<String> {
        val out = ArrayList<String>()
        if (paths.none { it.endsWith("AndroidManifest.xml") }) out += "No AndroidManifest.xml was generated: the app cannot be built without one."
        if (paths.none { it == "settings.gradle.kts" || it == "settings.gradle" }) out += "No settings.gradle(.kts) was generated."
        if (paths.none { it == "app/build.gradle.kts" || it == "app/build.gradle" }) out += "No app/build.gradle(.kts) was generated."
        return out
    }
}
