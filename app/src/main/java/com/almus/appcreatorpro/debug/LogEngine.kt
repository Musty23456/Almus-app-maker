package com.almus.appcreatorpro.debug

import com.almus.appcreatorpro.service.github.MiniJson

/** Splits a raw log into classified entries (build, error, warning, info, debug). One entry per log line. */
object LogParser {
    private val taskHeader = Regex("^> Task (:\\S+?)(?: FAILED)?$")
    private val wentWrong = Regex("^\\* What went wrong:")
    private val sectionEnd = Regex("^\\* (?:Try|Exception is|Get more help|Where)")

    private val errorPatterns = listOf(
        Regex("^e: "),
        Regex("(?:^|:\\s)error:"),
        Regex("AAPT: error"),
        Regex("\\bFAILED$"),
        Regex("^FAILURE:"),
        Regex("^BUILD FAILED"),
        Regex("^Caused by:"),
        Regex("^(?:[\\w.$]+\\.)?\\w*(?:Exception|Error)(?::|$)"),
        Regex("^Execution failed for task"),
        Regex("Could not (?:resolve|find|get|determine|GET|HEAD)"),
        Regex("SDK location not found|Compilation error|Unresolved reference|Manifest merger failed|resource linking failed"),
        Regex("^Process completed with exit code [1-9]"),
    )
    private val warningPatterns = listOf(
        Regex("^w: "),
        Regex("(?:^|:\\s)warning:"),
        Regex("^WARNING:"),
        Regex("\\[(?:deprecation|unchecked|removal)\\]"),
        Regex("Deprecated Gradle features"),
    )

    fun parse(raw: String, source: LogSource = LogSource.BUILD, redact: Boolean = true): List<LogEntry> {
        val lines = LogCleaner.lines(raw)
        val out = ArrayList<LogEntry>(lines.size)
        var task: String? = null
        var inWentWrong = false
        var previous: LogLevel? = null
        for ((index, line) in lines.withIndex()) {
            val text = line.text
            val trimmed = text.trim()
            taskHeader.matchEntire(trimmed)?.let { task = it.groupValues[1] }
            if (wentWrong.containsMatchIn(trimmed)) inWentWrong = true else if (sectionEnd.containsMatchIn(trimmed)) inWentWrong = false

            val level: LogLevel = when {
                trimmed.isEmpty() -> LogLevel.INFO
                line.marker == LogCleaner.Marker.ERROR -> LogLevel.ERROR
                line.marker == LogCleaner.Marker.WARNING -> LogLevel.WARNING
                line.marker == LogCleaner.Marker.DEBUG -> LogLevel.DEBUG
                errorPatterns.any { it.containsMatchIn(trimmed) } -> LogLevel.ERROR
                warningPatterns.any { it.containsMatchIn(trimmed) } -> LogLevel.WARNING
                inWentWrong && !wentWrong.containsMatchIn(trimmed) -> LogLevel.ERROR
                // Indented continuation lines (stack frames, javac source/caret/symbol lines) follow the line above.
                (text.first().isWhitespace() || trimmed.startsWith("at ") || trimmed.startsWith("symbol:") || trimmed.startsWith("location:")) &&
                    (previous == LogLevel.ERROR || previous == LogLevel.WARNING) -> previous!!
                else -> LogLevel.INFO
            }
            if (trimmed.isNotEmpty()) previous = level
            out += LogEntry(
                index = index,
                level = level,
                text = if (redact) LogCleaner.redact(text) else text,
                timestamp = line.timestamp,
                task = task,
                source = source,
            )
        }
        return out
    }
}

/** What the log viewer shows: which levels, the search text and options. */
data class LogFilter(
    val levels: Set<LogLevel> = LogLevel.values().toSet(),
    val query: String = "",
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val hideBlank: Boolean = true,
    /** Only entries of this Gradle task (null = all). */
    val task: String? = null,
) {
    private val compiled: Regex? = if (query.isEmpty()) null else buildRegex()

    private fun buildRegex(): Regex? = try {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex(if (useRegex) query else Regex.escape(query), options)
    } catch (e: Exception) {
        null
    }

    /** Message for a search text that is not a valid regular expression, otherwise null. */
    val queryError: String?
        get() = if (useRegex && query.isNotEmpty() && compiled == null) "Not a valid regular expression." else null

    val isActive: Boolean
        get() = levels.size < LogLevel.values().size || query.isNotEmpty() || task != null

    fun matches(entry: LogEntry): Boolean {
        if (entry.level !in levels) return false
        if (hideBlank && entry.text.isBlank()) return false
        if (task != null && entry.task != task) return false
        if (query.isEmpty()) return true
        val regex = compiled ?: return false
        return regex.containsMatchIn(entry.text)
    }

    fun apply(entries: List<LogEntry>): List<LogEntry> = entries.filter { matches(it) }

    /** Character ranges of [text] that match the search text (for highlighting). */
    fun highlightRanges(text: String): List<IntRange> {
        val regex = compiled ?: return emptyList()
        return regex.findAll(text).filter { it.value.isNotEmpty() }.map { it.range }.take(50).toList()
    }

    fun toggleLevel(level: LogLevel): LogFilter {
        val next = if (level in levels) levels - level else levels + level
        return copy(levels = next)
    }
}

data class LogStats(val total: Int, val errors: Int, val warnings: Int, val info: Int, val debug: Int) {
    companion object {
        fun of(entries: List<LogEntry>): LogStats {
            val visible = entries.filter { it.text.isNotBlank() }
            return LogStats(
                total = visible.size,
                errors = visible.count { it.level == LogLevel.ERROR },
                warnings = visible.count { it.level == LogLevel.WARNING },
                info = visible.count { it.level == LogLevel.INFO },
                debug = visible.count { it.level == LogLevel.DEBUG },
            )
        }
    }
}

enum class LogExportFormat(val extension: String, val mimeType: String) {
    TEXT("txt", "text/plain"),
    JSON("json", "application/json"),
}

/** Copy and export of the entries the viewer shows. Entries are already redacted by [LogParser]. */
object LogExport {

    fun toText(entries: List<LogEntry>, includeTimestamps: Boolean = false): String {
        val sb = StringBuilder()
        for (e in entries) {
            if (includeTimestamps && e.timestamp != null) sb.append(e.timestamp).append(' ')
            sb.append(e.text).append('\n')
        }
        return sb.toString()
    }

    fun toJson(entries: List<LogEntry>, projectName: String? = null, buildId: String? = null): String {
        val rows = entries.map { e ->
            linkedMapOf<String, Any?>(
                "line" to (e.index + 1).toLong(),
                "level" to e.level.name.lowercase(),
                "timestamp" to e.timestamp,
                "task" to e.task,
                "text" to e.text,
            )
        }
        val doc = linkedMapOf<String, Any?>(
            "format" to "almus-log-v1",
            "project" to projectName,
            "build" to buildId,
            "entries" to rows,
        )
        return MiniJson.stringify(doc)
    }

    fun render(entries: List<LogEntry>, format: LogExportFormat, projectName: String? = null, buildId: String? = null): String =
        when (format) {
            LogExportFormat.TEXT -> toText(entries)
            LogExportFormat.JSON -> toJson(entries, projectName, buildId)
        }

    /** A safe file name such as "My-App-build-log-2026-11-01.txt". */
    fun fileName(projectName: String?, format: LogExportFormat, dateIso: String): String {
        val base = (projectName ?: "almus").trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').ifEmpty { "almus" }.take(40)
        return "$base-build-log-$dateIso.${format.extension}"
    }
}
