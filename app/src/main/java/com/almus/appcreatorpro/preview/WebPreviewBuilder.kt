package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.SecretScanner

data class VirtualFile(val mime: String, val text: String)

/** Everything the sandboxed WebView may ever serve: a map of project path to (already instrumented) text. */
data class WebBundle(
    val entry: String,
    val files: Map<String, VirtualFile>,
    val warnings: List<String>,
    val breakpoints: List<Int>,
)

object WebPreviewBuilder {
    const val MAX_FILES = 300
    const val MAX_TOTAL_CHARS = 2_000_000
    private val textExtensions = setOf("html", "htm", "css", "js", "mjs", "json", "svg", "txt", "xml", "webmanifest", "csv", "md")

    fun mimeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "json", "webmanifest" -> "application/json"
        "svg" -> "image/svg+xml"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        else -> "text/plain"
    }

    /** Which project paths may enter the sandbox: text web files only; never secret files, build output or tool folders. */
    fun isServable(path: String): Boolean =
        !ContextPolicy.isHiddenPath(path) && !SecretScanner.isSensitivePath(path) && path.substringAfterLast('.', "").lowercase() in textExtensions

    private fun resolve(fromFile: String, ref: String): String? {
        val clean = ref.substringBefore('#').substringBefore('?').trim()
        if (clean.isEmpty()) return null
        val lower = clean.lowercase()
        if (lower.startsWith("#") || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("javascript:") || lower.startsWith("data:") || lower.startsWith("blob:")) return null
        if (lower.startsWith("http:") || lower.startsWith("https:") || clean.startsWith("//")) return null
        val dir = if (clean.startsWith("/")) "" else fromFile.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        for (seg in (if (dir.isEmpty()) clean.removePrefix("/") else "$dir/$clean").split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1) else return null
                else -> parts += seg
            }
        }
        return parts.joinToString("/").takeIf { it.isNotEmpty() }
    }

    private val refRegex = Regex("(?i)\\b(?:src|href)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")

    /**
     * @param texts the servable files of the project (path -> text), already limited by [isServable]
     * @param allPaths every project path, to tell "missing" from "exists but is binary"
     */
    fun build(entry: String, texts: Map<String, String>, allPaths: Set<String>, dark: Boolean, allowInternet: Boolean): WebBundle {
        val warnings = ArrayList<String>()
        if (entry !in texts) return WebBundle(entry, emptyMap(), listOf("$entry was not found in the project."), emptyList())
        val out = LinkedHashMap<String, VirtualFile>()
        val css = ArrayList<String>()
        var total = 0
        // The entry page always comes first, so a project over the limits still previews its main page.
        val order = listOf(entry) + texts.keys.filter { it != entry }
        for (path in order) {
            val text = texts.getValue(path)
            if (out.size >= MAX_FILES) { warnings += "More than $MAX_FILES files: the rest are not available to the preview."; break }
            total += text.length
            if (total > MAX_TOTAL_CHARS) { warnings += "The project's web files are larger than the preview limit; the rest are not available."; break }
            val ext = path.substringAfterLast('.', "").lowercase()
            val served = when (ext) {
                "html", "htm" -> {
                    val r = HtmlInstrumenter.instrument(text, path, dark, allowInternet)
                    if (path == entry && !r.hasViewport) warnings += "$path has no <meta name=\"viewport\">: mobile browsers lay such a page out at about 980 px wide and scale it down."
                    if (!allowInternet) for (x in r.externalRefs.distinct().take(8)) warnings += "$path uses an external resource ($x). Previews have no network access, so it will not load."
                    r.html
                }
                "css" -> { css += text; ThemeEmulation.rewriteCss(text, dark) }
                else -> text
            }
            out[path] = VirtualFile(mimeOf(path), served)
        }
        // Local references that do not resolve.
        for ((path, text) in texts) {
            if (path.substringAfterLast('.', "").lowercase() !in setOf("html", "htm")) continue
            for (m in refRegex.findAll(text)) {
                val ref = m.groups[1]?.value ?: m.groups[2]?.value ?: continue
                val target = resolve(path, ref) ?: continue
                if (target in out) continue
                if (target in allPaths) warnings += "$path refers to $target, which exists but is not a text web file, so the preview cannot serve it (images and other binary files are not available)."
                else if (target.substringAfterLast('.', "").isNotEmpty()) warnings += "$path refers to $target, which was not found in the project."
                if (warnings.size > 40) break
            }
        }
        return WebBundle(entry, out, warnings.distinct().take(40), BreakpointExtractor.extract(css))
    }
}
