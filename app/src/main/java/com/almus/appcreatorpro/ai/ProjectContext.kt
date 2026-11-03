package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.domain.model.ProjectType

/** Limits that keep a request small and free of unnecessary files. */
object ContextPolicy {
    const val MAX_FILES = 12
    const val MAX_FILE_CHARS = 40_000
    const val MAX_TOTAL_CHARS = 150_000
    const val MAX_STRUCTURE_PATHS = 250

    val textExtensions = setOf("kt", "kts", "java", "xml", "gradle", "properties", "pro", "toml", "json", "md", "txt", "yml", "yaml", "html", "css", "js")
    private val hiddenDirs = setOf("build", ".gradle", ".git", ".idea", "node_modules")

    fun isHiddenPath(path: String): Boolean = path.split('/').any { it in hiddenDirs }
    fun isTextPath(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in textExtensions
}

data class SkippedFile(val path: String, val reason: String)

data class ContextFile(val path: String, val content: String, val truncated: Boolean, val redactions: Int)

/** One line of the "this will be sent" list shown to the user before anything leaves the device. */
data class ContextPreviewItem(val path: String, val chars: Int, val truncated: Boolean, val redactions: Int)

/**
 * Exactly what a request will contain about the project. Built from an explicit list of files: nothing is added that
 * is not in [files], [structure] (paths only) or [dependencies] (coordinates only).
 */
data class ContextBundle(
    val projectName: String?,
    val languageLabel: String,
    val dependencies: List<String>,
    val structure: List<String>,
    val structureOmitted: Int,
    val files: List<ContextFile>,
    val skipped: List<SkippedFile>,
) {
    val totalChars: Int get() = files.sumOf { it.content.length }
    val redactionCount: Int get() = files.sumOf { it.redactions }

    fun preview(): List<ContextPreviewItem> = files.map { ContextPreviewItem(it.path, it.content.length, it.truncated, it.redactions) }

    /** Text added to the user's request. File text is data: the backend instructions tell the model not to obey it. */
    fun render(includeStructure: Boolean = true): String {
        val sb = StringBuilder()
        sb.append("PROJECT: ").append(projectName ?: "unknown").append('\n')
        sb.append("LANGUAGE: ").append(languageLabel).append('\n')
        if (dependencies.isNotEmpty()) {
            sb.append("DEPENDENCIES:\n")
            dependencies.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (includeStructure && structure.isNotEmpty()) {
            sb.append("STRUCTURE (").append(structure.size + structureOmitted).append(" files):\n")
            structure.forEach { sb.append("- ").append(it).append('\n') }
            if (structureOmitted > 0) sb.append("- ... and ").append(structureOmitted).append(" more\n")
        }
        if (files.isNotEmpty()) {
            sb.append("FILES:\n")
            for (f in files) {
                sb.append("=== FILE: ").append(f.path).append(if (f.truncated) " [truncated]" else "").append(" ===\n")
                sb.append(f.content)
                if (!f.content.endsWith("\n")) sb.append('\n')
                sb.append("=== END FILE ===\n")
            }
        }
        return sb.toString()
    }
}

object DependencyExtractor {
    private val coordinate = Regex(
        "(?:implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|debugImplementation|kapt|ksp|annotationProcessor|coreLibraryDesugaring)\\s*\\(?\\s*(?:platform\\(\\s*)?[\"']([^\"'\$]+:[^\"'\$]+)[\"']",
    )
    private val plugin = Regex("id\\s*\\(?\\s*[\"']([A-Za-z0-9_.\\-]+)[\"']")
    private val catalog = Regex("^\\s*[\\w\\-]+\\s*=\\s*\\{[^}]*group\\s*=\\s*\"([^\"]+)\"[^}]*name\\s*=\\s*\"([^\"]+)\"", RegexOption.MULTILINE)
    private val catalogModule = Regex("^\\s*[\\w\\-]+\\s*=\\s*\\{[^}]*module\\s*=\\s*\"([^\"]+)\"", RegexOption.MULTILINE)

    /** Dependency coordinates and plugin ids found in Gradle build files and a version catalog. Never file text. */
    fun extract(buildFileTexts: List<String>, limit: Int = 80): List<String> {
        val out = LinkedHashSet<String>()
        for (t in buildFileTexts) {
            coordinate.findAll(t).forEach { out += it.groupValues[1] }
            plugin.findAll(t).forEach { out += "plugin ${it.groupValues[1]}" }
            catalog.findAll(t).forEach { out += "${it.groupValues[1]}:${it.groupValues[2]}" }
            catalogModule.findAll(t).forEach { out += it.groupValues[1] }
        }
        return out.take(limit)
    }
}

/** Builds a [ContextBundle] from explicitly chosen files, with the safety rules applied. */
class ProjectContextBuilder(private val files: AiProjectFiles) {

    suspend fun build(projectName: String?, type: ProjectType?, requested: List<String>, includeStructure: Boolean = true): ContextBundle {
        val all = try { files.list() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val known = all.toHashSet()
        val visible = all.filter { !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }
        val structure = if (includeStructure) visible.take(ContextPolicy.MAX_STRUCTURE_PATHS) else emptyList()
        val omitted = if (includeStructure) maxOf(0, visible.size - structure.size) else 0

        val included = ArrayList<ContextFile>()
        val skipped = ArrayList<SkippedFile>()
        var total = 0
        for (path in requested.distinct()) {
            when {
                path !in known -> skipped += SkippedFile(path, "File not found in the project.")
                SecretScanner.isSensitivePath(path) -> skipped += SkippedFile(path, "Looks like it holds secrets, so it is never sent.")
                ContextPolicy.isHiddenPath(path) -> skipped += SkippedFile(path, "Build output and tool folders are never sent.")
                !ContextPolicy.isTextPath(path) -> skipped += SkippedFile(path, "Not a text source file.")
                included.size >= ContextPolicy.MAX_FILES -> skipped += SkippedFile(path, "At most ${ContextPolicy.MAX_FILES} files are sent at once.")
                else -> {
                    val raw = try { files.read(path) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                        skipped += SkippedFile(path, "Could not be read (too large or not text)."); continue
                    }
                    val red = SecretScanner.redact(raw)
                    val truncated = red.text.length > ContextPolicy.MAX_FILE_CHARS
                    val text = if (truncated) red.text.take(ContextPolicy.MAX_FILE_CHARS) else red.text
                    if (total + text.length > ContextPolicy.MAX_TOTAL_CHARS) {
                        skipped += SkippedFile(path, "The request would become too large.")
                    } else {
                        total += text.length
                        included += ContextFile(path, text, truncated, red.count)
                    }
                }
            }
        }
        val buildTexts = ArrayList<String>()
        for (p in all.filter { isBuildFile(it) }.take(6)) {
            try { buildTexts += files.read(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { /* skipped */ }
        }
        return ContextBundle(projectName, languageLabel(type, all), DependencyExtractor.extract(buildTexts), structure, omitted, included, skipped)
    }

    private fun isBuildFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == "build.gradle" || name == "build.gradle.kts" || name == "libs.versions.toml"
    }

    private fun languageLabel(type: ProjectType?, paths: List<String>): String = when (type) {
        ProjectType.ANDROID_KOTLIN -> "Kotlin (Android)"
        ProjectType.ANDROID_JAVA -> "Java (Android)"
        ProjectType.WEB -> "HTML / CSS / JavaScript"
        null -> {
            val kt = paths.count { it.endsWith(".kt") }
            val java = paths.count { it.endsWith(".java") }
            if (kt == 0 && java == 0) "unknown" else if (kt >= java) "Kotlin (Android)" else "Java (Android)"
        }
    }
}

/** Suggests which files are probably relevant to an instruction, by name. The user can always change the selection. */
object ContextSuggester {
    private val stop = setOf("the", "and", "for", "with", "into", "from", "this", "that", "change", "make", "add", "use", "using", "screen", "page", "should", "please", "modern", "new")

    fun suggest(instruction: String, paths: List<String>, limit: Int = 5): List<String> {
        val words = instruction.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }.toSet()
        if (words.isEmpty()) return emptyList()
        return paths.asSequence()
            .filter { ContextPolicy.isTextPath(it) && !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }
            .map { p ->
                val name = p.substringAfterLast('/').lowercase()
                var score = 0
                for (w in words) if (name.contains(w)) score += 3 else if (p.lowercase().contains(w)) score += 1
                if (score > 0 && (name.endsWith(".kt") || name.endsWith(".java") || p.contains("/layout/"))) score += 1
                p to score
            }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
            .toList()
    }
}
