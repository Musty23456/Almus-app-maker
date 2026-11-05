package com.almus.appcreatorpro.domain.github

import com.almus.appcreatorpro.domain.model.ProjectType

data class ValidationReport(
    val errors: List<String>,
    val warnings: List<String>,
    val fileCount: Int,
    val totalBytes: Long,
    /** Gradle version found in gradle-wrapper.properties, if any. */
    val detectedGradleVersion: String? = null,
) {
    val isValid: Boolean get() = errors.isEmpty()
}

/** Checks a project BEFORE anything is uploaded. Pure functions, no I/O. */
object CloudBuildValidator {
    const val MAX_FILES = 400
    const val MAX_TOTAL_BYTES = 5L * 1024 * 1024

    private val agpPlugin = Regex("android[._-]application")
    private val wrapperVersion = Regex("gradle-(\\d+\\.\\d+(?:\\.\\d+)?)-(?:bin|all)\\.zip")

    /** Things that must never be committed to a repository (secrets), found by pattern. */
    private val secretPatterns = listOf(
        Regex("gh[pousr]_[A-Za-z0-9]{30,}") to "a GitHub token",
        Regex("github_pat_[A-Za-z0-9_]{20,}") to "a GitHub token",
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----") to "a private key",
        Regex("AKIA[0-9A-Z]{16}") to "a cloud access key",
    )
    private val literalPassword = Regex("(?i)(store|key)Password\\s*=\\s*\"[^\"\\s]+\"")

    fun validate(type: ProjectType, files: Map<String, String>): ValidationReport {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val total = files.values.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

        if (type == ProjectType.WEB) {
            errors += "HTML/CSS/JavaScript projects cannot be compiled into an APK yet. Cloud builds support native Android (Kotlin or Java) projects."
            return ValidationReport(errors, warnings, files.size, total)
        }
        if (files.isEmpty()) {
            errors += "The project has no files."
            return ValidationReport(errors, warnings, 0, 0)
        }
        if (files.size > MAX_FILES) errors += "Too many files (${files.size}). The limit for a cloud build upload is $MAX_FILES."
        if (total > MAX_TOTAL_BYTES) errors += "The project is too large (${total / 1024} KB). The limit is ${MAX_TOTAL_BYTES / 1024} KB."

        val settings = files.keys.firstOrNull { it == "settings.gradle.kts" || it == "settings.gradle" }
        if (settings == null) {
            errors += "Missing settings.gradle(.kts) at the project root. Gradle needs it to find your modules."
        }
        if (files.keys.none { it == "build.gradle.kts" || it == "build.gradle" }) {
            errors += "Missing build.gradle(.kts) at the project root."
        }

        val gradleFiles = files.filterKeys { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") || it.endsWith(".versions.toml") }
        val appModuleFiles = gradleFiles.filter { (path, text) ->
            (path.endsWith("build.gradle.kts") || path.endsWith("build.gradle")) && agpPlugin.containsMatchIn(text) &&
                !Regex("apply\\s+false").containsMatchIn(lineWith(text, agpPlugin))
        }.keys
        if (appModuleFiles.isEmpty()) {
            errors += "No Android application module found: no build.gradle(.kts) applies the com.android.application plugin."
        } else if (settings != null) {
            val settingsText = files.getValue(settings)
            for (path in appModuleFiles) {
                val module = path.substringBeforeLast('/', "")
                if (module.isNotEmpty() && !settingsText.contains(module.replace('/', ':')) && !settingsText.contains(module)) {
                    warnings += "settings.gradle(.kts) does not seem to include the module \"$module\"."
                }
            }
        }

        if (files.keys.none { it.endsWith("src/main/AndroidManifest.xml") }) {
            errors += "Missing AndroidManifest.xml (expected at app/src/main/AndroidManifest.xml)."
        }
        if (type == ProjectType.ANDROID_KOTLIN && files.keys.none { it.endsWith(".kt") }) {
            warnings += "This is a Kotlin project but it contains no .kt files."
        }
        if (type == ProjectType.ANDROID_JAVA && files.keys.none { it.endsWith(".java") }) {
            warnings += "This is a Java project but it contains no .java files."
        }
        if (gradleFiles.none { (_, text) -> text.contains("namespace") }) {
            warnings += "No 'namespace' found in the app's build file. Android Gradle Plugin 8+ requires it."
        }

        for ((path, text) in files) {
            for ((pattern, what) in secretPatterns) {
                if (pattern.containsMatchIn(text)) {
                    errors += "\"$path\" appears to contain $what. Remove it before uploading: uploads are stored in your repository."
                    break
                }
            }
            if (path.endsWith(".gradle.kts") || path.endsWith(".gradle")) {
                if (literalPassword.containsMatchIn(text)) {
                    warnings += "\"$path\" has a signing password written in plain text. Do not commit real passwords."
                }
            }
        }

        val gradleVersion = files["gradle/wrapper/gradle-wrapper.properties"]
            ?.let { wrapperVersion.find(it)?.groupValues?.get(1) }
        if (files.containsKey("gradle/wrapper/gradle-wrapper.properties")) {
            warnings += "A Gradle wrapper file is present, but the cloud build uses an installed Gradle (version taken from it" +
                (gradleVersion?.let { ": $it" } ?: "") + ")."
        }

        return ValidationReport(errors, warnings, files.size, total, gradleVersion)
    }

    private fun lineWith(text: String, regex: Regex): String =
        text.lineSequence().firstOrNull { regex.containsMatchIn(it) }.orEmpty()
}

/** Validates the folder name inside the repository and the Gradle version before they reach the workflow. */
object CloudBuildInputValidator {
    private val dirPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*(/[A-Za-z0-9][A-Za-z0-9._-]*)*$")
    private val gradlePattern = Regex("^\\d{1,2}\\.\\d{1,2}(\\.\\d{1,2})?$")
    private val branchPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]*$")

    fun validateDirectory(dir: String): String? = when {
        dir.isBlank() -> "Enter a folder name for this project inside the repository."
        dir.length > 100 -> "Folder path is too long."
        dir.split('/').size > 4 -> "Folder path is nested too deeply (max 4 levels)."
        dir.split('/').any { it.startsWith(".") } -> "Folder names cannot start with a dot."
        dir.contains("..") -> "Folder path cannot contain '..'."
        !dirPattern.matches(dir) -> "Use only letters, digits, '.', '-', '_' and '/' (no spaces)."
        dir.startsWith(".github") -> "Choose a folder other than .github."
        else -> null
    }

    fun validateGradleVersion(version: String): String? =
        if (gradlePattern.matches(version)) null else "Gradle version must look like 8.9 or 8.10.2."

    fun validateBranchName(branch: String): String? = when {
        branch.isBlank() -> "Enter a branch name."
        branch.length > 100 -> "Branch name is too long."
        branch.contains("..") || branch.endsWith("/") || branch.endsWith(".lock") || branch.contains("//") ->
            "That is not a valid branch name."
        !branchPattern.matches(branch) -> "Use only letters, digits, '.', '-', '_' and '/'."
        else -> null
    }
}

/** Pulls the useful part out of a long Gradle log so the app can show it without a download. */
object LogExcerpt {
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s?")
    private val interesting = Regex(
        "(error:|^e: |FAILURE:|What went wrong|Execution failed|BUILD FAILED|Could not |not found|Unresolved reference|SDK location)",
        RegexOption.IGNORE_CASE,
    )

    fun extract(log: String, maxLines: Int = 20, maxChars: Int = 2_000): String {
        val lines = log.lineSequence()
            .map { timestamp.replace(ansi.replace(it, ""), "").trimEnd() }
            .filter { it.isNotBlank() }
            .map { LogRedactor.redact(it) }
            .toList()
        if (lines.isEmpty()) return ""
        val hits = lines.filter { interesting.containsMatchIn(it) }.distinct()
        val chosen = if (hits.isNotEmpty()) hits.takeLast(maxLines) else lines.takeLast(maxLines.coerceAtMost(15))
        return chosen.joinToString("\n").takeLast(maxChars)
    }
}
