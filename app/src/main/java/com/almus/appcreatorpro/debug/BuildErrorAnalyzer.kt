package com.almus.appcreatorpro.debug

/**
 * Reads a raw build log (Gradle / Kotlin / javac / AAPT2 / manifest merger) and returns the problems in it, each with
 * a category, severity, file and line (when the log has them) and a rule-based suggested solution.
 *
 * It only reports what the log says. It does not run a compiler and does not guess at problems the log does not
 * mention. [resolver] (optional) maps the machine paths in a CI log to files of the project, which is what makes
 * "open in editor" possible.
 */
class BuildErrorAnalyzer(
    private val resolver: ProjectPathResolver? = null,
    private val maxErrors: Int = 300,
    private val maxWarnings: Int = 100,
) {

    private class Draft(
        val rule: String,
        var category: ErrorCategory,
        val severity: Severity,
        val message: String,
        val file: String?,
        val line: Int?,
        val column: Int?,
        val suggestion: String,
        val summary: Boolean,
        val task: String?,
        val symbol: String?,
        val context: List<String>,
        val logIndex: Int,
    )

    fun analyze(rawLog: String): AnalysisResult {
        val lines = LogCleaner.lines(rawLog)
        val drafts = ArrayList<Draft>()
        var buildFailed: Boolean? = null
        var task: String? = null

        // Gradle prints "Build file 'x' line: N" under "* Where:", before the message that belongs to it.
        var pendingFile: String? = null
        var pendingLine: Int? = null
        var pendingAge = 0

        var inWentWrong = false
        var wrongStartIssues = 0
        val wrongLines = ArrayList<String>()
        var wrongStartIndex = 0

        fun contextAt(i: Int, count: Int): List<String> =
            (i until minOf(lines.size, i + count)).map { LogCleaner.redact(lines[it].text) }

        fun add(d: Draft) { drafts += d }

        fun endWentWrong() {
            if (!inWentWrong) return
            inWentWrong = false
            val emittedReal = drafts.drop(wrongStartIssues).any { !it.summary }
            val anyRealBefore = drafts.any { !it.summary && it.severity == Severity.ERROR }
            val useful = wrongLines
                .map { it.trim().trimStart('>', ' ').trim() }
                .filter { it.isNotEmpty() && !it.startsWith("Execution failed for task") && !it.startsWith("A failure occurred while executing") }
            if (!emittedReal && !anyRealBefore && useful.isNotEmpty()) {
                val message = useful.take(3).joinToString(" - ").take(400)
                add(
                    Draft(
                        rule = "gradle.unrecognised", category = ErrorCategory.GRADLE, severity = Severity.ERROR,
                        message = LogCleaner.redact(message), file = pendingFile, line = pendingLine, column = null,
                        suggestion = ErrorKnowledge.defaultSuggestion(ErrorCategory.GRADLE),
                        summary = false, task = task, symbol = null,
                        context = wrongLines.take(6).map { LogCleaner.redact(it) }, logIndex = wrongStartIndex,
                    ),
                )
            }
            wrongLines.clear()
        }

        var i = 0
        while (i < lines.size) {
            val cleaned = lines[i]
            val text = cleaned.text
            val trimmed = text.trim()
            if (pendingAge > 0 && --pendingAge == 0) { pendingFile = null; pendingLine = null }

            if (trimmed.isEmpty()) { i++; continue }

            // ---- structure markers --------------------------------------------------------------------------
            val taskMatch = TASK_HEADER.matchEntire(trimmed)
            if (taskMatch != null) {
                val m = taskMatch
                endWentWrong()
                task = m.groupValues[1]
                i++; continue
            }
            if (trimmed.startsWith("BUILD FAILED")) { buildFailed = true; i++; continue }
            if (trimmed.startsWith("BUILD SUCCESSFUL")) { if (buildFailed == null) buildFailed = false; i++; continue }
            if (WENT_WRONG.matches(trimmed)) {
                endWentWrong()
                inWentWrong = true
                wrongStartIssues = drafts.size
                wrongStartIndex = i
                i++; continue
            }
            if (SECTION_END.containsMatchIn(trimmed)) { endWentWrong() }
            val scriptLoc = SCRIPT_LOCATION.find(trimmed)
            if (scriptLoc != null) {
                val m = scriptLoc
                pendingFile = m.groupValues[1]
                pendingLine = m.groupValues[2].toIntOrNull()
                pendingAge = 14
                i++; continue
            }
            if (inWentWrong) wrongLines += text

            // ---- Kotlin compiler (K2 and K1 formats) ---------------------------------------------------------
            val k2 = KOTLIN_K2.matchEntire(text)
            val k1 = if (k2 == null) KOTLIN_K1.matchEntire(text) else null
            if (k2 != null || k1 != null) {
                val m = (k2 ?: k1)!!
                val level = m.groupValues[1]
                val file = m.groupValues[2]
                val ln = m.groupValues[3].toIntOrNull()
                val col = m.groupValues[4].toIntOrNull()
                val message = m.groupValues[5].trim()
                val isGradleScript = file.endsWith(".gradle.kts")
                val category = if (isGradleScript) ErrorCategory.GRADLE else ErrorCategory.KOTLIN_COMPILATION
                val found = ErrorKnowledge.firstMatch(ErrorKnowledge.kotlin, message)
                add(
                    draftFrom(
                        found, category, if (level == "w") Severity.WARNING else Severity.ERROR, message, file, ln, col,
                        suggestionFallback = if (isGradleScript) {
                            "The Gradle script has a code error. Open it at the shown line; Kotlin DSL scripts follow Kotlin rules, and unknown names usually mean a missing plugin or a typo."
                        } else null,
                        task = task, context = contextAt(i, 1), logIndex = i,
                    ),
                )
                i++; continue
            }

            // ---- kotlinc command line: "path/File.kt:12:5: error: unresolved reference 'x'." ---------------------------
            val cli = KOTLIN_CLI.matchEntire(text)
            if (cli != null) {
                val file = cli.groupValues[1]
                val isGradleScript = file.endsWith(".gradle.kts")
                add(
                    draftFrom(
                        ErrorKnowledge.firstMatch(ErrorKnowledge.kotlin, cli.groupValues[5].trim()),
                        if (isGradleScript) ErrorCategory.GRADLE else ErrorCategory.KOTLIN_COMPILATION,
                        if (cli.groupValues[4] == "warning") Severity.WARNING else Severity.ERROR,
                        cli.groupValues[5].trim(), file, cli.groupValues[2].toIntOrNull(), cli.groupValues[3].toIntOrNull(),
                        task = task, context = contextAt(i, 3), logIndex = i,
                    ),
                )
                i++; continue
            }

            // ---- javac ---------------------------------------------------------------------------------------
            val javacMatch = JAVAC.matchEntire(text)
            if (javacMatch != null) {
                val m = javacMatch
                val file = m.groupValues[1]
                val ln = m.groupValues[2].toIntOrNull()
                val level = m.groupValues[3]
                var message = m.groupValues[4].trim()
                // javac continues with the source line, a caret line and "symbol:" / "location:" details.
                var j = i + 1
                var symbol: String? = null
                val extra = ArrayList<String>()
                while (j < lines.size && j <= i + 5) {
                    val next = lines[j].text
                    if (next.isBlank() || JAVAC.matches(next) || KOTLIN_K2.matches(next) || next.startsWith("> Task") || next.startsWith("Note:")) break
                    extra += next
                    JAVA_SYMBOL.find(next)?.let { sm -> if (sm.groupValues[1] == "symbol") symbol = sm.groupValues[2] }
                    j++
                }
                val column = extra.firstOrNull { it.trim() == "^" }?.indexOf('^')?.let { it + 1 }
                val rendered = if (symbol != null && message.startsWith("cannot find symbol")) "cannot find symbol: $symbol" else message
                val found = ErrorKnowledge.firstMatch(ErrorKnowledge.java, message)
                add(
                    draftFrom(
                        found, ErrorCategory.JAVA_COMPILATION, if (level == "warning") Severity.WARNING else Severity.ERROR,
                        rendered, file, ln, column, task = task,
                        context = listOf(LogCleaner.redact(text)) + extra.map { LogCleaner.redact(it) },
                        logIndex = i, symbolOverride = symbol,
                    ),
                )
                i = j
                continue
            }

            // ---- manifest merger header: "path/AndroidManifest.xml:12:9-45 Error:" + message on next lines ------
            val headerMatch = MANIFEST_HEADER.matchEntire(text)
            if (headerMatch != null) {
                val m = headerMatch
                var j = i + 1
                val body = ArrayList<String>()
                while (j < lines.size && body.size < 3 && lines[j].text.isNotBlank() && lines[j].text.first().isWhitespace()) {
                    body += lines[j].text.trim()
                    j++
                }
                val message = body.joinToString(" ").ifBlank { "Manifest merger error" }
                val found = ErrorKnowledge.firstMatch(ErrorKnowledge.manifest, message)
                add(
                    draftFrom(
                        found, ErrorCategory.MANIFEST, m.groupValues[3].let { if (it.equals("Warning", true)) Severity.WARNING else Severity.ERROR },
                        message, m.groupValues[1], m.groupValues[2].toIntOrNull(), null, task = task,
                        context = listOf(LogCleaner.redact(text)) + body.map { LogCleaner.redact(it) }, logIndex = i,
                    ),
                )
                i = j
                continue
            }

            // ---- AAPT2 / resource and manifest errors that carry "file:line: error: message" ------------------
            val aaptMatch = AAPT_LOCATED.matchEntire(text)
            if (aaptMatch != null) {
                val m = aaptMatch
                val file = m.groupValues[1]
                if (!file.endsWith(".java") && !file.endsWith(".kt")) {
                    val ln = m.groupValues[2].toIntOrNull()
                    val message = m.groupValues[3].trim()
                    val isManifest = file.substringAfterLast('/') == "AndroidManifest.xml"
                    val rules = if (isManifest) ErrorKnowledge.manifest else ErrorKnowledge.resource
                    add(
                        draftFrom(
                            ErrorKnowledge.firstMatch(rules, message),
                            if (isManifest) ErrorCategory.MANIFEST else ErrorCategory.RESOURCE_LINKING,
                            Severity.ERROR, message, file, ln, null, task = task, context = contextAt(i, 1), logIndex = i,
                        ),
                    )
                    i++; continue
                }
            }
            val aaptParse = AAPT_PARSE.find(text)
            if (aaptParse != null) {
                val m = aaptParse
                // "Failed to compile resource file x.xml ... ParseError at [row,col]:[5,13] Message: ..."
                val file = m.groupValues[1]
                val ln = m.groupValues[2].toIntOrNull()
                val col = m.groupValues[3].toIntOrNull()
                val detail = text.substringAfter("Message:", "").trim().ifBlank {
                    lines.getOrNull(i + 1)?.text?.trim()?.takeIf { it.startsWith("Message:") }?.removePrefix("Message:")?.trim().orEmpty()
                }
                val message = "XML parse error: " + detail.ifBlank { "not well-formed" }
                add(
                    draftFrom(
                        ErrorKnowledge.firstMatch(ErrorKnowledge.resource, "ParseError"),
                        ErrorCategory.RESOURCE_LINKING, Severity.ERROR, message, file, ln, col,
                        task = task, context = contextAt(i, 1), logIndex = i,
                    ),
                )
                i++; continue
            }

            // ---- Kotlin DSL "Script compilation error:  Line 12: ... ^ message" -----------------------------------
            val scriptLine = SCRIPT_LINE.find(text)
            if (scriptLine != null) {
                val m = scriptLine
                var j = i + 1
                var message = "Script compilation error"
                while (j < lines.size && j <= i + 3) {
                    val caret = SCRIPT_CARET.find(lines[j].text)
                    if (caret != null) { message = caret.groupValues[1].trim(); break }
                    j++
                }
                add(
                    draftFrom(
                        ErrorKnowledge.firstMatch(ErrorKnowledge.kotlin, message), ErrorCategory.GRADLE, Severity.ERROR, message,
                        pendingFile, m.groupValues[1].toIntOrNull(), null,
                        suggestionFallback = "The Gradle script has a code error on this line. Unknown names usually mean a missing plugin, a missing import or a typo.",
                        task = task, context = contextAt(i, 3), logIndex = i,
                    ),
                )
                i = maxOf(i + 1, j + 1)
                continue
            }

            // ---- single-line Gradle / AGP / dependency messages ---------------------------------------------------
            val bare = trimmed.trimStart('>', ' ').trim()
            val gradleMatch = ErrorKnowledge.firstMatch(ErrorKnowledge.gradle, bare)
            if (gradleMatch != null) {
                val (rule, m) = gradleMatch
                val category = rule.category ?: ErrorCategory.GRADLE
                val severity = rule.severity ?: Severity.ERROR
                val symbolRaw = if (rule.symbolGroup > 0) m.groupValues.getOrNull(rule.symbolGroup).orEmpty() else ""
                var file: String? = null
                var ln: Int? = null
                // "Manifest merger failed : Attribute application@x ... from AndroidManifest.xml:5:9-36 ..."
                MERGER_FROM.find(bare)?.let { fm -> file = fm.groupValues[1]; ln = fm.groupValues[2].toIntOrNull() }
                if (file == null && rule.id.startsWith("gradle.") && pendingFile != null) { file = pendingFile; ln = pendingLine }
                add(
                    Draft(
                        rule = rule.id, category = category, severity = severity,
                        message = LogCleaner.redact(bare).take(400), file = file, line = ln, column = null,
                        suggestion = rule.suggest(m), summary = rule.summary, task = task,
                        symbol = symbolRaw.trimEnd('.').ifEmpty { null },
                        context = contextAt(i, 2), logIndex = i,
                    ),
                )
                i++; continue
            }
            if (MERGER_FAILED.containsMatchIn(bare)) {
                // The merger prints its explanation on following, indented lines ("is also present at ...", "Suggestion: ...").
                val more = ArrayList<String>()
                var k = i + 1
                while (k < lines.size && more.size < 4 && lines[k].text.isNotBlank() && lines[k].text.first().isWhitespace()) {
                    more += lines[k].text.trim()
                    k++
                }
                val message = (bare.substringAfter("Manifest merger failed", "").trimStart(' ', ':').ifBlank { "Manifest merger failed" } +
                    (if (more.isEmpty()) "" else " " + more.joinToString(" "))).trim()
                val found = ErrorKnowledge.firstMatch(ErrorKnowledge.manifest, message)
                var file: String? = null
                var ln: Int? = null
                MERGER_FROM.find(bare)?.let { fm -> file = fm.groupValues[1]; ln = fm.groupValues[2].toIntOrNull() }
                add(
                    draftFrom(
                        found, ErrorCategory.MANIFEST, Severity.ERROR, LogCleaner.redact(message).take(400), file, ln, null,
                        task = task, context = contextAt(i, 1 + more.size), logIndex = i,
                    ),
                )
                i = k; continue
            }
            val manifestMsg = MANIFEST_MESSAGE.find(bare)
            if (manifestMsg != null) {
                val found = ErrorKnowledge.firstMatch(ErrorKnowledge.manifest, bare)
                if (found != null && found.first.id == "manifest.exported") {
                    add(draftFrom(found, ErrorCategory.MANIFEST, Severity.ERROR, bare.take(400), pendingFile, pendingLine, null, task = task, context = contextAt(i, 2), logIndex = i))
                    i++; continue
                }
            }

            // ---- Java/Kotlin warnings and generic "error:" lines that nothing above understood --------------------
            if (GENERIC_ERROR.containsMatchIn(trimmed) && !trimmed.startsWith("at ") && !trimmed.startsWith("Caused by")) {
                if (!trimmed.contains("0 errors", true) && !trimmed.contains("no errors", true)) {
                    add(
                        Draft(
                            rule = "log.error-line", category = ErrorCategory.OTHER, severity = Severity.ERROR,
                            message = LogCleaner.redact(trimmed).take(400), file = null, line = null, column = null,
                            suggestion = ErrorKnowledge.defaultSuggestion(ErrorCategory.OTHER),
                            summary = false, task = task, symbol = null, context = contextAt(i, 2), logIndex = i,
                        ),
                    )
                }
            }
            i++
        }
        endWentWrong()

        return finish(drafts, buildFailed)
    }

    private fun draftFrom(
        found: Pair<MessageRule, MatchResult>?,
        defaultCategory: ErrorCategory,
        defaultSeverity: Severity,
        message: String,
        file: String?,
        line: Int?,
        column: Int?,
        suggestionFallback: String? = null,
        task: String?,
        context: List<String>,
        logIndex: Int,
        symbolOverride: String? = null,
    ): Draft {
        val rule = found?.first
        val m = found?.second
        val symbol = symbolOverride
            ?: if (rule != null && m != null && rule.symbolGroup > 0) m.groupValues.getOrNull(rule.symbolGroup).orEmpty().trim('\'', '`').ifEmpty { null } else null
        val severity = when {
            defaultSeverity == Severity.WARNING -> Severity.WARNING
            rule?.severity != null -> rule.severity
            else -> defaultSeverity
        }
        return Draft(
            rule = rule?.id?.takeUnless { it.endsWith(".generic") } ?: "${defaultCategory.name.lowercase().replace('_', '-')}.other",
            category = rule?.category ?: defaultCategory,
            severity = severity,
            message = LogCleaner.redact(message).take(500),
            file = file, line = line, column = column,
            suggestion = if (rule != null && m != null && !rule.id.endsWith(".generic")) rule.suggest(m)
            else suggestionFallback ?: (if (rule != null && m != null) rule.suggest(m) else ErrorKnowledge.defaultSuggestion(defaultCategory)),
            summary = rule?.summary == true, task = task, symbol = symbol, context = context, logIndex = logIndex,
        )
    }

    private fun finish(drafts: List<Draft>, buildFailed: Boolean?): AnalysisResult {
        val hasReal = drafts.any { !it.summary && it.severity == Severity.ERROR }
        val kept = ArrayList<Draft>()
        for (d in drafts) {
            if (d.summary && hasReal) continue
            kept += d
        }
        // When only summaries are left, one of each rule is enough.
        val seenSummary = HashSet<String>()
        val filtered = kept.filter { !it.summary || seenSummary.add(it.rule) }

        val issues = LinkedHashMap<String, BuildIssue>()
        var omitted = 0
        var errors = 0
        var warnings = 0
        for (d in filtered) {
            val projectPath = resolver?.resolve(d.file)
            val id = Fingerprint.of(d.rule, d.file ?: "", d.line, d.message)
            if (issues.containsKey(id)) continue
            when (d.severity) {
                Severity.ERROR -> if (++errors > maxErrors) { omitted++; continue }
                else -> if (++warnings > maxWarnings) { omitted++; continue }
            }
            issues[id] = BuildIssue(
                id = id, rule = d.rule, category = d.category, severity = d.severity, message = d.message,
                file = d.file, projectPath = projectPath, line = d.line, column = d.column,
                suggestion = d.suggestion, source = IssueSource.BUILD_LOG, confidence = Confidence.HIGH,
                task = d.task, symbol = d.symbol, context = d.context, logIndex = d.logIndex,
            )
        }
        var list = issues.values.toList()
        if (list.none { it.severity == Severity.ERROR } && buildFailed == true) {
            val message = "The build failed, but no specific error could be recognised in this log."
            list = list + BuildIssue(
                id = Fingerprint.of("gradle.unrecognised", "", null, message),
                rule = "gradle.unrecognised", category = ErrorCategory.GRADLE, severity = Severity.ERROR, message = message,
                file = null, projectPath = null, line = null, column = null,
                suggestion = "The log may be cut off (only the end of a long log is loaded), or the failure is outside the build tool (for example a runner problem). Open the log and read the last lines before 'BUILD FAILED'.",
                source = IssueSource.BUILD_LOG,
            )
        }
        val sorted = list.sortedWith(compareBy<BuildIssue> { it.severity.rank }.thenBy { it.logIndex ?: Int.MAX_VALUE })
        return AnalysisResult(sorted, buildFailed, omitted)
    }

    private companion object {
        val TASK_HEADER = Regex("^> Task (:\\S+?)(?: FAILED)?$")
        val WENT_WRONG = Regex("^\\* What went wrong:\\s*$")
        val SECTION_END = Regex("^\\* (?:Try|Exception is|Get more help)")
        val SCRIPT_LOCATION = Regex("^(?:Build file|Settings file|Script|Initialization script) '(.+?)' line: (\\d+)")
        val KOTLIN_K2 = Regex("^([ew]): (?:file://)?(.+?\\.kts?):(\\d+):(\\d+):? (.*)$")
        val KOTLIN_K1 = Regex("^([ew]): (?:file://)?(.+?\\.kts?): ?\\((\\d+), (\\d+)\\):? (.*)$")
        val KOTLIN_CLI = Regex("^(.+?\\.kts?):(\\d+):(\\d+): (error|warning): (.*)$")
        val JAVAC = Regex("^(.+?\\.java):(\\d+): (error|warning): (.*)$")
        val JAVA_SYMBOL = Regex("^\\s*(symbol|location):\\s+(?:(?:variable|method|class|interface|package)\\s+)?(\\S+)")
        val MANIFEST_HEADER = Regex("^(.*?AndroidManifest\\.xml):(\\d+):\\d+(?:-\\d+(?::\\d+)?)?\\s+(Error|Warning):?\\s*$")
        val AAPT_LOCATED = Regex("^(.+?\\.\\w+):(\\d+)(?::\\d+(?:-\\d+)?)?: (?:AAPT: )?error: (.*)$")
        val AAPT_PARSE = Regex("Failed to compile (?:values )?resource file (\\S+?)(?:\\.|:)?\\s.*?ParseError at \\[row,col\\]:\\[(\\d+),(\\d+)\\]")
        val SCRIPT_LINE = Regex("^\\s*Line (\\d+):")
        val SCRIPT_CARET = Regex("^\\s*\\^\\s*(.*)$")
        val MERGER_FROM = Regex("from (?:\\S*[/\\\\])?(AndroidManifest\\.xml):(\\d+)")
        val MERGER_FAILED = Regex("^Manifest merger failed\\s*:")
        val MANIFEST_MESSAGE = Regex("android:exported needs to be explicitly specified")
        val GENERIC_ERROR = Regex("(?:^|\\s)(?:error|ERROR|FATAL|Fatal):\\s|^error\\b|\\bFAILED$")
    }
}
