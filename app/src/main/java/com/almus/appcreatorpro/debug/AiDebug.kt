package com.almus.appcreatorpro.debug

import com.almus.appcreatorpro.service.github.MiniJson

/**
 * Phase 8 "AI debugging preparation". NO AI model is called anywhere in Phase 8 and NO AI answer is written into the
 * app. This file defines the contract a later phase (Gemini) plugs into:
 *
 *   build log + issues + code context --[AiRequestBuilder]--> AiDebugRequest
 *   AiDebugRequest --[AiPromptRenderer]--> prompt text (provider independent)
 *   provider (future Gemini client) --> response text --[AiResponseParser]--> AiDebugResponse
 *   AiDebugResponse --[AiPatchConverter]--> FixProposal (origin AI) --> the normal fix list
 *
 * so an AI patch is previewed, applied, rejected and undone exactly like a rule-based fix, and is checked against the
 * real file text before it is offered. Nothing is ever applied automatically.
 */
enum class AiTask(val label: String) {
    EXPLAIN("Explain the cause"),
    SUGGEST_FIXES("Suggest code corrections"),
    GENERATE_PATCH("Generate a patch"),
}

data class AiIssueContext(
    val id: String,
    val rule: String,
    val category: String,
    val severity: String,
    val message: String,
    val path: String?,
    val line: Int?,
    val column: Int?,
    val ruleBasedSuggestion: String?,
)

/** A piece of a project file shown to the model. [snippet] holds lines [startLine]..[endLine]. */
data class AiFileContext(val path: String, val startLine: Int, val endLine: Int, val snippet: String, val fileHash: String)

data class AiDebugRequest(
    val task: AiTask,
    val projectName: String?,
    val buildId: String?,
    val buildFailed: Boolean?,
    val issues: List<AiIssueContext>,
    /** Redacted excerpt of the log around the errors (never the whole log). */
    val logExcerpt: String,
    val files: List<AiFileContext>,
    val schemaVersion: Int = 1,
) {
    /** True when source code leaves the device with this request: the UI must ask the user before sending. */
    val containsSourceCode: Boolean get() = files.isNotEmpty()
}

/** Replace the exact text [find] (must occur exactly once in the file) with [replace]. */
data class AiPatchEdit(val find: String, val replace: String)

data class AiPatch(
    val path: String,
    val title: String,
    val explanation: String,
    val edits: List<AiPatchEdit> = emptyList(),
    /** Alternative to [edits]: a unified diff for this one file. */
    val unifiedDiff: String? = null,
)

data class AiDebugResponse(
    val summary: String,
    val rootCause: String?,
    val explanation: String,
    val patches: List<AiPatch>,
    val model: String? = null,
)

sealed interface AiResult {
    data class Success(val response: AiDebugResponse) : AiResult
    data class Unavailable(val reason: String) : AiResult
    data class Failed(val message: String, val retryable: Boolean) : AiResult
}

sealed interface AiAvailability {
    data object Ready : AiAvailability
    data class NotReady(val reason: String) : AiAvailability
}

/** What a future Gemini integration implements. */
interface AiDebugProvider {
    val id: String
    val displayName: String
    suspend fun availability(): AiAvailability
    suspend fun analyze(request: AiDebugRequest): AiResult
}

/** The provider of Phase 8: says honestly that no AI is connected. It never returns an answer. */
object NoAiProvider : AiDebugProvider {
    override val id: String = "none"
    override val displayName: String = "No AI connected"
    private const val REASON = "AI debugging is not connected yet. The build analysis, code checks and fix suggestions on this screen are rule-based."
    override suspend fun availability(): AiAvailability = AiAvailability.NotReady(REASON)
    override suspend fun analyze(request: AiDebugRequest): AiResult = AiResult.Unavailable(REASON)
}

object AiRequestBuilder {
    const val MAX_ISSUES = 8
    const val MAX_LOG_CHARS = 12_000
    const val CONTEXT_LINES = 12
    const val MAX_FILES = 4

    private val sensitiveName = Regex("(?i)(?:local\\.properties|\\.jks|\\.keystore|\\.p12|\\.pem|\\.env|secret|credential|password|token)")

    /**
     * Builds the request. Only files named by the most important issues are included, only the lines around the
     * problem, never files that look like secrets, and everything goes through log redaction.
     */
    suspend fun build(
        task: AiTask,
        projectName: String?,
        buildId: String?,
        analysis: AnalysisResult,
        issues: List<BuildIssue>,
        logEntries: List<LogEntry>,
        files: ProjectFileAccess?,
    ): AiDebugRequest {
        val chosen = issues.sortedWith(compareBy<BuildIssue> { it.severity.rank }.thenBy { it.logIndex ?: Int.MAX_VALUE }).take(MAX_ISSUES)
        val contexts = chosen.map {
            AiIssueContext(
                it.id, it.rule, it.category.name, it.severity.name, LogCleaner.redact(it.message), it.projectPath ?: it.file, it.line, it.column, it.suggestion,
            )
        }
        val fileContexts = ArrayList<AiFileContext>()
        if (files != null) {
            for (issue in chosen) {
                val path = issue.projectPath ?: continue
                if (fileContexts.size >= MAX_FILES) break
                if (sensitiveName.containsMatchIn(path) || fileContexts.any { it.path == path && issue.line != null && issue.line in it.startLine..it.endLine }) continue
                val text = try { files.read(path) } catch (e: Exception) { continue }
                val lines = TextEdits.lines(text)
                val center = issue.line ?: 1
                val start = maxOf(1, center - CONTEXT_LINES)
                val end = minOf(lines.size, center + CONTEXT_LINES)
                val snippet = (start..end).joinToString("\n") { LogCleaner.redact(lines[it - 1].trimEnd('\r')) }
                fileContexts += AiFileContext(path, start, end, snippet, TextEdits.hash(text))
            }
        }
        return AiDebugRequest(task, projectName, buildId, analysis.buildFailed, contexts, excerpt(logEntries, chosen), fileContexts)
    }

    private fun excerpt(entries: List<LogEntry>, issues: List<BuildIssue>): String {
        if (entries.isEmpty()) return ""
        val keep = java.util.TreeSet<Int>()
        for (i in issues) i.logIndex?.let { idx -> for (k in (idx - 3)..(idx + 6)) if (k in entries.indices) keep.add(k) }
        if (keep.isEmpty()) for (k in maxOf(0, entries.size - 40) until entries.size) keep.add(k)
        val sb = StringBuilder()
        var last = -2
        for (k in keep) {
            if (k != last + 1 && sb.isNotEmpty()) sb.append("...\n")
            sb.append(LogCleaner.redact(entries[k].text)).append('\n')
            last = k
            if (sb.length > MAX_LOG_CHARS) break
        }
        return sb.toString().take(MAX_LOG_CHARS)
    }
}

/** Provider independent prompt. A Gemini client sends this text and passes the answer to [AiResponseParser]. */
object AiPromptRenderer {
    const val RESPONSE_SCHEMA = """{
  "summary": "one sentence",
  "rootCause": "what actually causes the problem, or null",
  "explanation": "short explanation for a beginner",
  "patches": [
    {
      "path": "project-relative file path exactly as given",
      "title": "short title",
      "explanation": "why this change fixes it",
      "edits": [ { "find": "exact existing text, must occur once in the file", "replace": "new text" } ]
    }
  ]
}"""

    fun render(request: AiDebugRequest): String {
        val sb = StringBuilder()
        sb.append("You are helping debug an Android project (Kotlin/Java/Gradle) built on GitHub Actions.\n")
        sb.append("Task: ").append(request.task.label).append(".\n")
        sb.append("Only use the information below. If it is not enough, say so in \"explanation\" and return no patches.\n")
        sb.append("Reply with ONE JSON object and nothing else, in exactly this shape:\n").append(RESPONSE_SCHEMA).append("\n")
        if (request.task == AiTask.EXPLAIN) sb.append("Return an empty \"patches\" array.\n")
        sb.append("\nProject: ").append(request.projectName ?: "unknown").append('\n')
        sb.append("Build result: ").append(when (request.buildFailed) { true -> "failed"; false -> "succeeded"; null -> "unknown" }).append("\n\nIssues:\n")
        request.issues.forEachIndexed { i, it ->
            sb.append(i + 1).append(". [").append(it.severity).append("/").append(it.category).append("] ").append(it.message)
            if (it.path != null) sb.append("  (").append(it.path).append(it.line?.let { l -> ":$l" } ?: "").append(")")
            sb.append('\n')
        }
        if (request.logExcerpt.isNotBlank()) sb.append("\nLog excerpt:\n").append(request.logExcerpt).append('\n')
        for (f in request.files) {
            sb.append("\nFile ").append(f.path).append(" lines ").append(f.startLine).append("-").append(f.endLine).append(":\n")
            f.snippet.lines().forEachIndexed { i, line -> sb.append(f.startLine + i).append(": ").append(line).append('\n') }
        }
        return sb.toString()
    }
}

object AiResponseParser {
    sealed interface Parsed {
        data class Ok(val response: AiDebugResponse) : Parsed
        data class Bad(val reason: String) : Parsed
    }

    /** Accepts plain JSON or JSON inside a Markdown code fence. */
    fun parse(text: String, model: String? = null): Parsed {
        val json = extractJson(text) ?: return Parsed.Bad("The answer did not contain a JSON object.")
        val obj = try { MiniJson.parseObject(json) } catch (e: Exception) { return Parsed.Bad("The answer is not valid JSON: ${e.message}") }
        val summary = (obj["summary"] as? String).orEmpty()
        val explanation = (obj["explanation"] as? String).orEmpty()
        if (summary.isBlank() && explanation.isBlank()) return Parsed.Bad("The answer has neither a summary nor an explanation.")
        val patches = ArrayList<AiPatch>()
        for (p in (obj["patches"] as? List<*>).orEmpty()) {
            val m = p as? Map<*, *> ?: continue
            val path = m["path"] as? String ?: continue
            val edits = (m["edits"] as? List<*>).orEmpty().mapNotNull { e ->
                val em = e as? Map<*, *> ?: return@mapNotNull null
                val find = em["find"] as? String ?: return@mapNotNull null
                val replace = em["replace"] as? String ?: return@mapNotNull null
                AiPatchEdit(find, replace)
            }
            val diff = m["unifiedDiff"] as? String
            if (edits.isEmpty() && diff.isNullOrBlank()) continue
            patches += AiPatch(path, (m["title"] as? String).orEmpty().ifBlank { "Change $path" }, (m["explanation"] as? String).orEmpty(), edits, diff)
        }
        return Parsed.Ok(AiDebugResponse(summary, obj["rootCause"] as? String, explanation, patches, model))
    }

    private fun extractJson(text: String): String? {
        val fence = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL).find(text)
        if (fence != null) return fence.groupValues[1]
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }
}

/** A model's patch turned into a [FixProposal] - or the reason it could not be used. */
sealed interface PatchConversion {
    data class Ok(val proposal: FixProposal) : PatchConversion
    data class Rejected(val patch: AiPatch, val reason: String) : PatchConversion
}

object AiPatchConverter {
    const val MAX_CHANGED_LINES = 200

    /** Checks every patch against the real file text. Patches that do not match exactly are rejected, never "fixed up". */
    suspend fun convert(response: AiDebugResponse, files: ProjectFileAccess, issueId: String? = null): List<PatchConversion> =
        response.patches.map { convertOne(it, files, issueId) }

    private suspend fun convertOne(patch: AiPatch, files: ProjectFileAccess, issueId: String?): PatchConversion {
        val path = patch.path.trim().replace('\\', '/')
        if (path.isEmpty() || path.startsWith("/") || path.split('/').any { it == ".." } || path.contains(':')) return PatchConversion.Rejected(patch, "The file path is not a project path.")
        val text = try { files.read(path) } catch (e: Exception) { return PatchConversion.Rejected(patch, "The file `$path` does not exist in the project.") }
        val edits: List<CodeEdit> = if (patch.edits.isNotEmpty()) {
            val lineIndex = SourceText.LineIndex(text)
            val converted = ArrayList<CodeEdit>()
            for (e in patch.edits) {
                if (e.find.isEmpty()) return PatchConversion.Rejected(patch, "A change has no text to find.")
                val first = text.indexOf(e.find)
                if (first < 0) return PatchConversion.Rejected(patch, "The text to replace was not found in `$path`; the file may differ from what the model saw.")
                if (text.indexOf(e.find, first + 1) >= 0) return PatchConversion.Rejected(patch, "The text to replace occurs more than once in `$path`, so the change is ambiguous.")
                val startLine = lineIndex.lineOf(first)
                val endLine = lineIndex.lineOf(first + e.find.length - (if (e.find.endsWith("\n")) 1 else 0))
                val lines = TextEdits.lines(text)
                val prefix = text.substring(lineIndex.startOf(startLine), first)
                val suffixStart = first + e.find.length
                val lineEnd = lineIndex.endOf(endLine)
                val suffix = if (suffixStart <= lineEnd) text.substring(suffixStart, lineEnd) else ""
                val replaced = (prefix + e.replace + suffix).trimEnd('\r').split("\n")
                converted += CodeEdit(startLine, endLine + 1, replaced)
                if (lines.isEmpty()) return PatchConversion.Rejected(patch, "The file is empty.")
            }
            converted
        } else {
            when (val r = UnifiedDiff.toEdits(patch.unifiedDiff.orEmpty(), text)) {
                is UnifiedDiff.Result.Ok -> r.edits
                is UnifiedDiff.Result.Bad -> return PatchConversion.Rejected(patch, r.reason)
            }
        }
        val changed = edits.sumOf { it.removedCount + it.newLines.size }
        if (changed > MAX_CHANGED_LINES) return PatchConversion.Rejected(patch, "The patch changes too many lines ($changed) to review safely.")
        when (val applied = TextEdits.apply(text, edits)) {
            is TextEdits.Result.Invalid -> return PatchConversion.Rejected(patch, applied.reason)
            is TextEdits.Result.Ok -> if (applied.text == text) return PatchConversion.Rejected(patch, "The patch does not change anything.")
        }
        val proposal = FixProposal(
            id = Fingerprint.of("ai-fix", path, edits.joinToString("|") { "${it.startLine}-${it.endLineExclusive}:${it.newLines.joinToString("\u0001")}" }),
            issueId = issueId, title = patch.title, explanation = patch.explanation, path = path, edits = edits,
            origin = FixOrigin.AI, confidence = Confidence.LOW, baseHash = TextEdits.hash(text),
        )
        return PatchConversion.Ok(proposal)
    }
}

/** Minimal unified-diff reader for a single file: context and removed lines must match the file exactly. */
object UnifiedDiff {
    sealed interface Result {
        data class Ok(val edits: List<CodeEdit>) : Result
        data class Bad(val reason: String) : Result
    }

    private val hunkHeader = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    fun toEdits(diff: String, fileText: String): Result {
        val fileLines = TextEdits.lines(fileText).map { it.trimEnd('\r') }
        val lines = diff.lines()
        val edits = ArrayList<CodeEdit>()
        var i = 0
        while (i < lines.size && !hunkHeader.containsMatchIn(lines[i])) i++
        if (i >= lines.size) return Result.Bad("The diff has no @@ hunk.")
        while (i < lines.size) {
            val h = hunkHeader.find(lines[i]) ?: run { i++; null } ?: continue
            val declaredStart = h.groupValues[1].toInt()
            i++
            val oldBlock = ArrayList<String>()
            val newBlock = ArrayList<String>()
            while (i < lines.size && !lines[i].startsWith("@@")) {
                val l = lines[i]
                when {
                    l.startsWith("+") && !l.startsWith("+++") -> newBlock += l.substring(1)
                    l.startsWith("-") && !l.startsWith("---") -> oldBlock += l.substring(1)
                    l.startsWith(" ") -> { oldBlock += l.substring(1); newBlock += l.substring(1) }
                    l.isEmpty() -> { oldBlock += ""; newBlock += "" }
                    l.startsWith("\\") -> Unit
                    else -> break
                }
                i++
            }
            while (oldBlock.isNotEmpty() && oldBlock.last().isEmpty() && newBlock.isNotEmpty() && newBlock.last().isEmpty() && i >= lines.size) { oldBlock.removeAt(oldBlock.size - 1); newBlock.removeAt(newBlock.size - 1) }
            if (oldBlock.isEmpty() && newBlock.isEmpty()) continue
            val at = locate(fileLines, oldBlock, declaredStart) ?: return Result.Bad("A hunk does not match the file (the file differs from what the diff was made for).")
            edits += CodeEdit(at, at + oldBlock.size, newBlock)
        }
        return if (edits.isEmpty()) Result.Bad("The diff changes nothing.") else Result.Ok(edits)
    }

    /** 1-based line where [block] matches exactly: at the declared line, otherwise the only other match. */
    private fun locate(file: List<String>, block: List<String>, declared: Int): Int? {
        fun matchesAt(start: Int): Boolean {
            if (start < 1 || start - 1 + block.size > file.size) return false
            for (k in block.indices) if (file[start - 1 + k] != block[k]) return false
            return true
        }
        if (block.isEmpty()) return declared.coerceIn(1, file.size + 1)
        if (matchesAt(declared)) return declared
        val all = (1..file.size).filter { matchesAt(it) }
        return all.singleOrNull()
    }
}
