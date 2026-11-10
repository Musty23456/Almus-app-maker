package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.SecretScanner
import com.almus.appcreatorpro.debug.TextEdits
import com.almus.appcreatorpro.domain.model.ProjectType

/**
 * What a preview may see of the project: the real files plus an optional in-memory DRAFT (for example UI that the AI
 * just generated). It can only read. Writing or deleting throws, so no preview code path can change the project.
 */
class PreviewFiles(private val base: AiProjectFiles, private val drafts: Map<String, String> = emptyMap()) : AiProjectFiles {
    override suspend fun list(): List<String> = (base.list() + drafts.keys).distinct().sorted()
    override suspend fun exists(path: String): Boolean = path in drafts || base.exists(path)
    override suspend fun read(path: String): String = drafts[path] ?: base.read(path)
    override suspend fun write(path: String, content: String) { throw UnsupportedOperationException("Previews never write to the project.") }
    override suspend fun delete(path: String) { throw UnsupportedOperationException("Previews never delete project files.") }
}

object PreviewDetector {
    const val MAX_TARGETS = 80
    private val layoutRegex = Regex("(^|/)res/layout[^/]*/[^/]+\\.xml$")
    private val valuesRegex = Regex("(^|/)res/values(-[A-Za-z0-9+-]*)?/[^/]+\\.xml$")

    fun isLayout(path: String) = layoutRegex.containsMatchIn(path)
    fun isValues(path: String) = valuesRegex.containsMatchIn(path)

    /** Files whose change should refresh the preview. */
    fun isPreviewRelevant(path: String): Boolean {
        if (ContextPolicy.isHiddenPath(path) || SecretScanner.isSensitivePath(path)) return false
        return when (path.substringAfterLast('.', "").lowercase()) {
            "html", "htm", "css", "js", "mjs", "json", "svg", "kt", "dart", "yaml" -> true
            "xml" -> path.contains("/res/") || path.startsWith("res/")
            else -> false
        }
    }

    /**
     * @param paths every project path
     * @param composableSources Kotlin files that contain `@Composable` (path -> text)
     */
    fun detect(paths: List<String>, composableSources: Map<String, String>): List<PreviewTarget> {
        val out = ArrayList<PreviewTarget>()
        val visible = paths.filter { !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }

        val html = visible.filter { it.substringAfterLast('.', "").lowercase().let { e -> e == "html" || e == "htm" } }
            .sortedWith(compareBy<String>({ it != "index.html" }, { it.count { c -> c == '/' } }, { it }))
        for (p in html.take(30)) out += PreviewTarget(PreviewKind.WEB, p, p)

        val layouts = visible.filter { isLayout(it) }.sortedWith(compareBy<String>({ !it.contains("/res/layout/") }, { !it.contains("activity_main") }, { it }))
        for (p in layouts.take(40)) out += PreviewTarget(PreviewKind.ANDROID_XML, p, p.substringAfter("res/"))

        val previews = ArrayList<PreviewTarget>()
        val others = ArrayList<PreviewTarget>()
        for (p in composableSources.keys.sorted()) {
            for (f in ComposeSource.functions(KtLexer.tokenize(composableSources.getValue(p)))) {
                val t = PreviewTarget(PreviewKind.ANDROID_COMPOSE, p, f.name + "  (" + p.substringAfterLast('/') + ")", f.name)
                if (f.isPreview) previews += t else others += t
            }
        }
        out += previews
        out += others

        val pubspec = visible.firstOrNull { it == "pubspec.yaml" || it.endsWith("/pubspec.yaml") }
        if (pubspec != null) {
            val main = pubspec.removeSuffix("pubspec.yaml") + "lib/main.dart"
            out += PreviewTarget(PreviewKind.FLUTTER, if (main in visible) main else pubspec, "Flutter app")
        }
        return out.take(MAX_TARGETS)
    }

    fun defaultTarget(targets: List<PreviewTarget>, type: ProjectType?): PreviewTarget? {
        if (targets.isEmpty()) return null
        return when (type) {
            ProjectType.WEB -> targets.firstOrNull { it.kind == PreviewKind.WEB }
            ProjectType.ANDROID_KOTLIN, ProjectType.ANDROID_JAVA ->
                targets.firstOrNull { it.kind == PreviewKind.ANDROID_COMPOSE } ?: targets.firstOrNull { it.kind == PreviewKind.ANDROID_XML }
            null -> null
        } ?: targets.first()
    }

    /** A cheap fingerprint of everything that can change what the preview shows. Used to detect edits for auto-refresh. */
    suspend fun fingerprint(files: AiProjectFiles): String {
        val sb = StringBuilder()
        var total = 0
        var count = 0
        for (p in files.list()) {
            if (!isPreviewRelevant(p)) continue
            if (++count > 400) break
            val text = try { files.read(p) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { continue }
            total += text.length
            sb.append(p).append(':').append(text.length).append(':').append(TextEdits.hash(text)).append('\n')
            if (total > 3_000_000) break
        }
        return TextEdits.hash(sb.toString())
    }
}
