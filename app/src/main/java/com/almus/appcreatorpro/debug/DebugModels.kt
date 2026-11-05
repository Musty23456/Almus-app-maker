package com.almus.appcreatorpro.debug

import java.security.MessageDigest

/**
 * Phase 8: shared vocabulary of the debugging system. Everything in the `debug` package is plain Kotlin (no Android
 * classes) so it can be unit-tested on a normal JVM and reused by a future offline build engine.
 */

/** What kind of problem an issue is. The first seven are the build error categories asked for in Phase 8. */
enum class ErrorCategory(val label: String) {
    KOTLIN_COMPILATION("Kotlin compilation"),
    JAVA_COMPILATION("Java compilation"),
    GRADLE("Gradle"),
    MISSING_DEPENDENCY("Dependency"),
    MANIFEST("Manifest"),
    RESOURCE_LINKING("Resource linking"),
    SDK_CONFIGURATION("Android SDK / JDK setup"),

    /** Static diagnostics only: code that compiles but is probably not what was meant (unused code...). */
    CODE_QUALITY("Code quality"),

    /** Static diagnostics only: code that may fail or misbehave while the app runs. */
    RUNTIME_RISK("Potential runtime issue"),
    OTHER("Other"),
}

enum class Severity(val label: String, val rank: Int) {
    ERROR("Error", 0),
    WARNING("Warning", 1),
    INFO("Info", 2),
}

/** Where an issue came from. Only BUILD_LOG issues are facts reported by a real compiler/Gradle run. */
enum class IssueSource(val label: String) {
    BUILD_LOG("Build log"),
    STATIC_ANALYSIS("Static check"),
    AI("AI"),
}

/** How sure the tool is. Build-log issues are HIGH; static checks are heuristics and say so. */
enum class Confidence(val label: String) {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low"),
}

/**
 * One problem. [file] is the path exactly as it appeared in the log (often an absolute path on the build machine);
 * [projectPath] is the matching file inside the project when there is one, and is what navigation uses.
 */
data class BuildIssue(
    val id: String,
    val rule: String,
    val category: ErrorCategory,
    val severity: Severity,
    val message: String,
    val file: String?,
    val projectPath: String?,
    val line: Int?,
    val column: Int?,
    val suggestion: String?,
    val source: IssueSource,
    val confidence: Confidence = Confidence.HIGH,
    /** Gradle task that was running when the problem was reported (for example ":app:compileDebugKotlin"). */
    val task: String? = null,
    /** The name the message is about (an unresolved symbol, a missing dependency coordinate...). */
    val symbol: String? = null,
    /** A few raw log lines around the problem (cleaned and redacted). */
    val context: List<String> = emptyList(),
    /** Index of the matching entry in the parsed log, so the log viewer can jump to it. */
    val logIndex: Int? = null,
) {
    /** True when the dashboard can open the file in the editor. */
    val canNavigate: Boolean get() = projectPath != null

    val fileName: String? get() = (projectPath ?: file)?.substringAfterLast('/')?.ifEmpty { null }

    /** "MainActivity.kt:12:5", "MainActivity.kt:12" or just the file name. */
    val locationLabel: String?
        get() {
            val name = fileName ?: return null
            val l = line ?: return name
            val c = column?.let { ":$it" }.orEmpty()
            return "$name:$l$c"
        }
}

enum class LogLevel(val label: String, val letter: Char) {
    DEBUG("Debug", 'D'),
    INFO("Info", 'I'),
    WARNING("Warning", 'W'),
    ERROR("Error", 'E'),
}

enum class LogSource(val label: String) {
    BUILD("Build"),

    /** Reserved for the future Logcat integration. */
    LOGCAT("Logcat"),
}

data class LogEntry(
    val index: Int,
    val level: LogLevel,
    val text: String,
    val timestamp: String? = null,
    /** Logcat tag, when the source has one. */
    val tag: String? = null,
    /** Gradle task the line belongs to, when known. */
    val task: String? = null,
    val source: LogSource = LogSource.BUILD,
)

/** Result of analysing one build log. */
data class AnalysisResult(
    val issues: List<BuildIssue>,
    /** true = the log says BUILD FAILED, false = BUILD SUCCESSFUL, null = the log does not say. */
    val buildFailed: Boolean?,
    /** Problems that were left out because there were too many of them. */
    val omittedWarnings: Int = 0,
) {
    val errorCount: Int get() = issues.count { it.severity == Severity.ERROR }
    val warningCount: Int get() = issues.count { it.severity == Severity.WARNING }

    companion object {
        val EMPTY = AnalysisResult(emptyList(), null)
    }
}

/** Where to open a file in the editor. [line] and [column] are 1-based. */
data class NavigationTarget(val path: String, val line: Int?, val column: Int?)

/** Stable short id for an issue or a fix, so a rejected suggestion stays rejected when the analysis runs again. */
object Fingerprint {
    fun of(vararg parts: Any?): String {
        val joined = parts.joinToString("\u0001") { it?.toString().orEmpty() }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }
}
