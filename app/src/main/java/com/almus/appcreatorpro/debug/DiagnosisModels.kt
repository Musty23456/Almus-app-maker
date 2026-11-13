package com.almus.appcreatorpro.debug

/**
 * Phase 22: vocabulary of the diagnosis layer. Everything in this file is plain Kotlin (no Android classes).
 *
 * The diagnosis layer sits ON TOP of [BuildErrorAnalyzer]: the analyzer finds what the log says, the diagnosis layer
 * works out what the findings mean together (which one is the root cause, which ones follow from it, how bad each is,
 * which files are involved and how sure we are). It only uses evidence that is in the log or in the project's file
 * list; where evidence is missing it says so instead of guessing.
 */

/** A finer classification than [ErrorCategory]: it is what the user sees as "Error Category". */
enum class DiagnosisCategory(val label: String) {
    GRADLE("Gradle build script / configuration"),
    KOTLIN_COMPILATION("Kotlin compilation"),
    JAVA_COMPILATION("Java compilation"),
    ANDROID_SDK("Android SDK / JDK setup"),
    DEPENDENCY_CONFLICT("Dependency conflict"),
    DEPENDENCY_RESOLUTION("Dependency resolution"),
    MANIFEST("Android manifest"),
    XML("XML syntax"),
    RESOURCE_LINKING("Resource processing / linking"),
    NETWORK("Network"),
    GITHUB_ACTIONS("GitHub Actions workflow / runner"),
    JVM_EXCEPTION("JVM exception (stack trace)"),
    STATIC_CHECK("Static code check (heuristic)"),
    UNKNOWN("Not recognised"),
}

/** How urgent a problem is. Ordered by [rank] (0 = most urgent). */
enum class SeverityLevel(val label: String, val rank: Int) {
    /** The build cannot succeed until this is fixed, and nothing else explains it: fix this first. */
    CRITICAL("Critical", 0),

    /** The build fails here too, but the evidence says it most likely follows from a root cause. */
    HIGH("High", 1),

    /** Does not stop the build today but is expected to (deprecations that are being removed, risky configuration). */
    MEDIUM("Medium", 2),
    LOW("Low", 3),
    INFO("Info", 4),
}

enum class ErrorRole(val label: String) {
    /** A problem that has to be fixed itself (it is not explained by another error in the log). */
    ROOT_CAUSE("Root cause"),

    /** Reported by the build, but most likely a consequence of [ROOT_CAUSE] (see [CausalLink] for the evidence). */
    SECONDARY("Secondary (follows from another error)"),

    /** A warning, a summary line or a note: it does not need its own fix. */
    INFORMATIONAL("Informational"),
}

/** Where the fix is most likely to be made. */
enum class FixScope(val label: String) {
    PROJECT_CODE("Your source code and resources"),
    PROJECT_CONFIG("Your build / workflow configuration"),
    ENVIRONMENT("The build environment (network, runner, installed tools)"),
    UNKNOWN("Not known from the log"),
}

/** How a file location was found. Only [LOG_LOCATION] and [STACK_TRACE] are stated by the build output itself. */
enum class LocationBasis(val label: String) {
    LOG_LOCATION("named in the build log"),
    STACK_TRACE("named in a stack trace"),
    INFERRED("inferred (likely place to look, not stated in the log)"),
}

data class AffectedFile(
    /** The project-relative path when the file was found in the project, otherwise the path as the log printed it. */
    val path: String,
    val inProject: Boolean,
    val line: Int? = null,
    val column: Int? = null,
    val basis: LocationBasis,
    val note: String? = null,
) {
    val label: String get() = path + (line?.let { ":$it" + (column?.let { c -> ":$c" } ?: "") } ?: "")
}

/**
 * How sure the tool is that its suggested solution is right. [score] is 0..100 and only meant for comparing; [level] is
 * what is shown. [sufficient] is false when the evidence does not support a specific solution at all: the UI then says
 * so instead of presenting the generic hint as an answer.
 */
data class DiagnosticConfidence(
    val level: Confidence,
    val score: Int,
    val reasons: List<String>,
    val sufficient: Boolean,
)

/** [rootIssueId] is the id of the [BuildIssue] this error most likely follows from, and why we think so. */
data class CausalLink(val rootIssueId: String, val reason: String, val confidence: Confidence)

/** One method call of a stack trace. [projectPath] is set only when the frame's file matches exactly one project file. */
data class StackFrame(
    val declaringClass: String,
    val method: String,
    val file: String?,
    val line: Int?,
    val projectPath: String?,
    /** True for code of the JDK, Kotlin, Android, Gradle and other well-known frameworks. */
    val framework: Boolean,
) {
    val isProject: Boolean get() = projectPath != null
    val text: String get() = "$declaringClass.$method(" + (file ?: "Unknown Source") + (line?.let { ":$it" } ?: "") + ")"
}

data class ExceptionInfo(
    /** Fully qualified exception class as printed (for example `java.lang.NullPointerException`). */
    val type: String,
    val message: String?,
    val frames: List<StackFrame>,
    /** Frames that were cut by the analyzer's cap or by the "... N more" marker of the trace. */
    val omittedFrames: Int = 0,
) {
    val simpleName: String get() = type.substringAfterLast('.').substringAfterLast('$')
}

/** One stack trace found in a log, outermost exception first, root cause (the deepest "Caused by") last. */
data class StackTraceAnalysis(
    val id: String,
    /** Index of the first line of the trace in the cleaned log (same numbering as the log viewer). */
    val startLogIndex: Int,
    val endLogIndex: Int,
    val chain: List<ExceptionInfo>,
) {
    val outermost: ExceptionInfo get() = chain.first()
    val rootCause: ExceptionInfo get() = chain.last()

    /** All frames that belong to project files, de-duplicated, in the order they appear. */
    val projectFrames: List<StackFrame>
        get() = chain.flatMap { it.frames }.filter { it.isProject }.distinctBy { Triple(it.projectPath, it.line, it.method) }

    /** The project frame closest to the top of the root cause's stack: where the failing call came from in your code. */
    val firstProjectFrame: StackFrame?
        get() = rootCause.frames.firstOrNull { it.isProject } ?: projectFrames.firstOrNull()

    /** True when no frame belongs to the project: the trace says where a library failed, not which of your lines caused it. */
    val frameworkOnly: Boolean get() = projectFrames.isEmpty()
}

/** One problem with everything the diagnosis layer worked out about it. */
data class DiagnosedIssue(
    val issue: BuildIssue,
    val category: DiagnosisCategory,
    val role: ErrorRole,
    val severity: SeverityLevel,
    val scope: FixScope,
    /** Set when [role] is [ErrorRole.SECONDARY]. */
    val link: CausalLink?,
    val affectedFiles: List<AffectedFile>,
    /** Why this kind of error happens. Null when there is no built-in explanation (the UI then says so). */
    val why: String?,
    /** The suggested solution, or null when the evidence does not support a specific one. */
    val solution: String?,
    val confidence: DiagnosticConfidence,
    val stackTrace: StackTraceAnalysis? = null,
    /** How many identical problems (same rule and name) were merged into this one. 1 = not merged. */
    val occurrences: Int = 1,
    /** Ids of the other issues that were merged into this one. */
    val mergedIssueIds: List<String> = emptyList(),
    /** Ids of the issues that follow from this one (only for root causes). */
    val secondaryIds: List<String> = emptyList(),
) {
    val id: String get() = issue.id
}

/** The complete diagnosis of one build log. */
data class Diagnosis(
    val issues: List<DiagnosedIssue>,
    val stackTraces: List<StackTraceAnalysis>,
    val buildFailed: Boolean?,
    /** Things the log (or this analysis) could not tell: truncated log, unmatched dependencies, and so on. */
    val limitations: List<String>,
) {
    val rootCauses: List<DiagnosedIssue> get() = issues.filter { it.role == ErrorRole.ROOT_CAUSE }
    val secondary: List<DiagnosedIssue> get() = issues.filter { it.role == ErrorRole.SECONDARY }
    val informational: List<DiagnosedIssue> get() = issues.filter { it.role == ErrorRole.INFORMATIONAL }

    /** The problem to fix first: the most severe root cause, earliest in the log. Null when there is nothing to fix. */
    val primary: DiagnosedIssue? get() = rootCauses.firstOrNull()

    fun find(id: String): DiagnosedIssue? = issues.firstOrNull { it.id == id || id in it.mergedIssueIds }

    fun secondaryOf(root: DiagnosedIssue): List<DiagnosedIssue> = issues.filter { it.link?.rootIssueId == root.id }

    val isEmpty: Boolean get() = issues.isEmpty()

    companion object {
        val EMPTY = Diagnosis(emptyList(), emptyList(), null, emptyList())
    }
}
