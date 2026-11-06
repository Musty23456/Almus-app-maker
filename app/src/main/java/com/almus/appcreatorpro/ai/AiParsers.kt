package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiPatchEdit
import com.almus.appcreatorpro.domain.validation.PackageNameSuggester
import com.almus.appcreatorpro.domain.validation.PackageNameValidator
import com.almus.appcreatorpro.domain.validation.ProjectNameValidator
import com.almus.appcreatorpro.service.github.MiniJson

sealed interface Parsed<out T> {
    data class Ok<out T>(val value: T) : Parsed<T>
    data class Bad(val reason: String) : Parsed<Nothing>
}

/** Finds the JSON object in a model answer (plain, or inside a Markdown fence) and parses it. Never repairs broken JSON. */
object AiJson {
    /**
     * Finds "```json { ... } ```" without a regular expression. A regex built at class-load time that Android's regex
     * engine dislikes would throw an Error (not an Exception) and close the whole app the moment an answer arrives.
     */
    private fun fencedJson(text: String): String? {
        var open = text.indexOf("```")
        while (open >= 0) {
            var i = open + 3
            if (text.startsWith("json", i)) i += 4
            while (i < text.length && text[i].isWhitespace()) i++
            if (i < text.length && text[i] == '{') {
                var close = text.indexOf('}', i)
                while (close >= 0) {
                    var k = close + 1
                    while (k < text.length && text[k].isWhitespace()) k++
                    if (text.startsWith("```", k)) return text.substring(i, close + 1)
                    close = text.indexOf('}', close + 1)
                }
            }
            open = text.indexOf("```", open + 1)
        }
        return null
    }

    fun parseObject(text: String, truncated: Boolean): Parsed<Map<String, Any?>> {
        val json = extract(text)
        val cutOff = "The answer was cut off because it was too long. Ask for a smaller project or fewer files."
        if (json == null) return Parsed.Bad(if (truncated) cutOff else "The answer did not contain a JSON object.")
        return try {
            Parsed.Ok(MiniJson.parseObject(json))
        } catch (e: Exception) {
            Parsed.Bad(if (truncated) cutOff else "The answer is not valid JSON: ${e.message.orEmpty().take(120)}")
        }
    }

    private fun extract(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        fencedJson(text)?.let { return it }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    fun strings(v: Any?, max: Int = 60, maxLen: Int = 300): List<String> =
        (v as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() }?.take(maxLen) }.take(max)
}

// ---------------------------------------------------------------- project plan

data class PlanTechnology(val name: String, val purpose: String)
data class PlanStep(val title: String, val detail: String)

data class ProjectPlan(
    val projectName: String,
    val packageName: String,
    val language: String,
    val summary: String,
    val technologies: List<PlanTechnology>,
    val dependencies: List<String>,
    val architecture: String,
    val structure: List<String>,
    val steps: List<PlanStep>,
    val risks: List<String>,
) {
    /** The approved plan, as text for the "generate project" request. */
    fun toPromptText(): String = buildString {
        append("Project name: ").append(projectName).append('\n')
        append("Package: ").append(packageName).append('\n')
        append("Language: ").append(language).append('\n')
        append("Summary: ").append(summary).append('\n')
        if (technologies.isNotEmpty()) { append("Technologies:\n"); technologies.forEach { append("- ").append(it.name).append(": ").append(it.purpose).append('\n') } }
        if (dependencies.isNotEmpty()) { append("Dependencies:\n"); dependencies.forEach { append("- ").append(it).append('\n') } }
        if (architecture.isNotBlank()) append("Architecture: ").append(architecture).append('\n')
        if (structure.isNotEmpty()) { append("Planned files:\n"); structure.forEach { append("- ").append(it).append('\n') } }
        if (steps.isNotEmpty()) { append("Steps:\n"); steps.forEach { append("- ").append(it.title).append(": ").append(it.detail).append('\n') } }
    }
}

/** Project names and packages from a model are untrusted input: they are cleaned and validated like user input. */
object GeneratedNames {
    fun projectName(raw: String?): String {
        val cleaned = raw.orEmpty().filter { it.isLetterOrDigit() || it in " _.-" }.trim().trimStart('.', '-', '_', ' ').take(ProjectNameValidator.MAX_LENGTH).trim()
        return if (ProjectNameValidator.validate(cleaned).isValid) cleaned else "AiApp"
    }

    fun packageName(raw: String?, projectName: String): String {
        val candidate = raw.orEmpty().trim()
        if (PackageNameValidator.validate(candidate).isValid) return candidate
        val suggested = PackageNameSuggester.suggest(projectName)
        return if (PackageNameValidator.validate(suggested).isValid) suggested else "com.example.aiapp"
    }
}

object ProjectPlanParser {
    fun parse(text: String, truncated: Boolean = false): Parsed<ProjectPlan> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val summary = (obj["summary"] as? String).orEmpty().trim()
        if (summary.isEmpty()) return Parsed.Bad("The plan has no summary.")
        val name = GeneratedNames.projectName(obj["project_name"] as? String)
        val techs = (obj["technologies"] as? List<*>).orEmpty().mapNotNull {
            val m = it as? Map<*, *> ?: return@mapNotNull null
            val n = (m["name"] as? String)?.trim().orEmpty()
            if (n.isEmpty()) null else PlanTechnology(n.take(80), (m["purpose"] as? String).orEmpty().trim().take(200))
        }.take(20)
        val steps = (obj["steps"] as? List<*>).orEmpty().mapNotNull {
            val m = it as? Map<*, *> ?: return@mapNotNull null
            val t = (m["title"] as? String)?.trim().orEmpty()
            if (t.isEmpty()) null else PlanStep(t.take(100), (m["detail"] as? String).orEmpty().trim().take(400))
        }.take(20)
        val language = ((obj["language"] as? String).orEmpty().lowercase().takeIf { it == "java" } ?: "kotlin")
        return Parsed.Ok(
            ProjectPlan(
                projectName = name,
                packageName = GeneratedNames.packageName(obj["package_name"] as? String, name),
                language = language,
                summary = summary.take(800),
                technologies = techs,
                dependencies = AiJson.strings(obj["dependencies"], 40),
                architecture = (obj["architecture"] as? String).orEmpty().trim().take(800),
                structure = AiJson.strings(obj["structure"], 40, 200),
                steps = steps,
                risks = AiJson.strings(obj["risks"], 10),
            ),
        )
    }
}

// ---------------------------------------------------------------- generated project

data class GeneratedFile(val path: String, val content: String)

data class GeneratedProject(
    val projectName: String,
    val packageName: String,
    val summary: String,
    val files: List<GeneratedFile>,
    val notes: List<String>,
)

object GeneratedProjectParser {
    /** Parses the structure only. Paths and contents are checked by [GeneratedFileValidator], which reports every problem. */
    fun parse(text: String, truncated: Boolean = false): Parsed<GeneratedProject> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val rawFiles = obj["files"] as? List<*> ?: return Parsed.Bad("The answer has no \"files\" list.")
        val files = ArrayList<GeneratedFile>()
        for ((i, f) in rawFiles.withIndex()) {
            val m = f as? Map<*, *> ?: return Parsed.Bad("File ${i + 1} is not an object.")
            val path = m["path"] as? String ?: return Parsed.Bad("File ${i + 1} has no path.")
            val content = m["content"] as? String ?: return Parsed.Bad("File \"$path\" has no content.")
            files += GeneratedFile(path, content)
        }
        if (files.isEmpty()) return Parsed.Bad("The answer contains no files.")
        val name = GeneratedNames.projectName(obj["project_name"] as? String)
        return Parsed.Ok(
            GeneratedProject(
                projectName = name,
                packageName = GeneratedNames.packageName(obj["package_name"] as? String, name),
                summary = (obj["summary"] as? String).orEmpty().trim().take(800),
                files = files,
                notes = AiJson.strings(obj["notes"], 10),
            ),
        )
    }
}

// ---------------------------------------------------------------- existing project editing

data class SelectedFile(val path: String, val reason: String)
data class EditSelection(val summary: String, val files: List<SelectedFile>)

object EditSelectionParser {
    fun parse(text: String, knownPaths: Set<String>, truncated: Boolean = false): Parsed<EditSelection> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val files = (obj["files"] as? List<*>).orEmpty().mapNotNull {
            val m = it as? Map<*, *> ?: return@mapNotNull null
            val path = (m["path"] as? String)?.trim() ?: return@mapNotNull null
            // Only paths that really exist: a made-up path is dropped, never "corrected".
            if (path in knownPaths) SelectedFile(path, (m["reason"] as? String).orEmpty().trim().take(200)) else null
        }.distinctBy { it.path }.take(ContextPolicy.MAX_FILES)
        return Parsed.Ok(EditSelection((obj["summary"] as? String).orEmpty().trim().take(500), files))
    }
}

/** What the model wants to do to one file: either the complete new [content] or targeted [edits]. */
data class RawChange(val path: String, val action: String, val explanation: String, val content: String?, val edits: List<AiPatchEdit>)
data class EditProposal(val summary: String, val changes: List<RawChange>)

object EditProposalParser {
    fun parse(text: String, truncated: Boolean = false): Parsed<EditProposal> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val list = obj["changes"] as? List<*> ?: return Parsed.Bad("The answer has no \"changes\" list.")
        val changes = list.mapNotNull {
            val m = it as? Map<*, *> ?: return@mapNotNull null
            val path = (m["path"] as? String) ?: return@mapNotNull null
            val edits = (m["edits"] as? List<*>).orEmpty().mapNotNull { e ->
                val em = e as? Map<*, *> ?: return@mapNotNull null
                val find = em["find"] as? String ?: return@mapNotNull null
                val replace = em["replace"] as? String ?: return@mapNotNull null
                AiPatchEdit(find, replace)
            }
            RawChange(path, (m["action"] as? String).orEmpty().lowercase(), (m["explanation"] as? String).orEmpty().trim().take(400), m["content"] as? String, edits)
        }
        return Parsed.Ok(EditProposal((obj["summary"] as? String).orEmpty().trim().take(800), changes))
    }
}

// ---------------------------------------------------------------- code review

enum class ReviewCategory(val wire: String, val label: String) {
    QUALITY("quality", "Code quality"),
    SECURITY("security", "Security"),
    PERFORMANCE("performance", "Performance"),
    ARCHITECTURE("architecture", "Architecture"),
    BEST_PRACTICE("best_practice", "Best practice"),
    ;

    companion object {
        fun fromWire(s: String?): ReviewCategory = entries.firstOrNull { it.wire == s?.lowercase() } ?: QUALITY
    }
}

enum class ReviewSeverity(val wire: String, val label: String, val rank: Int) {
    HIGH("high", "High", 0), MEDIUM("medium", "Medium", 1), LOW("low", "Low", 2), INFO("info", "Info", 3),
    ;

    companion object {
        fun fromWire(s: String?): ReviewSeverity = entries.firstOrNull { it.wire == s?.lowercase() } ?: INFO
    }
}

data class ReviewFinding(
    val category: ReviewCategory,
    val severity: ReviewSeverity,
    val path: String?,
    val line: Int?,
    val title: String,
    val detail: String,
    val suggestion: String,
)

data class ReviewResult(val summary: String, val findings: List<ReviewFinding>)

object ReviewParser {
    const val MAX_FINDINGS = 25

    fun parse(text: String, truncated: Boolean = false): Parsed<ReviewResult> {
        val obj = when (val p = AiJson.parseObject(text, truncated)) { is Parsed.Ok -> p.value; is Parsed.Bad -> return Parsed.Bad(p.reason) }
        val summary = (obj["summary"] as? String).orEmpty().trim()
        val findings = (obj["findings"] as? List<*>).orEmpty().mapNotNull {
            val m = it as? Map<*, *> ?: return@mapNotNull null
            val title = (m["title"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            ReviewFinding(
                category = ReviewCategory.fromWire(m["category"] as? String),
                severity = ReviewSeverity.fromWire(m["severity"] as? String),
                path = (m["path"] as? String)?.trim()?.takeIf { p -> p.isNotEmpty() && p != "null" },
                line = (m["line"] as? Number)?.toInt()?.takeIf { l -> l > 0 },
                title = title.take(160),
                detail = (m["detail"] as? String).orEmpty().trim().take(800),
                suggestion = (m["suggestion"] as? String).orEmpty().trim().take(800),
            )
        }.sortedBy { it.severity.rank }.take(MAX_FINDINGS)
        if (summary.isEmpty() && findings.isEmpty()) return Parsed.Bad("The review is empty.")
        return Parsed.Ok(ReviewResult(summary.take(800), findings))
    }
}
