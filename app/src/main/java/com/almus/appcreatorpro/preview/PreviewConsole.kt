package com.almus.appcreatorpro.preview

import com.almus.appcreatorpro.ai.SecretScanner

/** A tap in inspect mode: the source position of the element and its tag. */
data class PickEvent(val path: String, val line: Int, val tag: String)

/**
 * The preview console. Page output comes from the WebView's console callback (no JavaScript bridge is installed in the
 * sandbox, so the page has no way to call into the app). Script errors arrive there too, with file and line, and
 * the file is mapped back to the project path so the editor can jump to it. Everything is redacted for secrets and
 * capped, so a page that logs in a loop cannot grow memory without limit.
 */
object PreviewConsoleLog {
    const val MAX_ENTRIES = 500
    const val MAX_MESSAGE = 800

    fun levelOf(webViewLevel: String?): ConsoleLevel = when (webViewLevel?.uppercase()) {
        "ERROR" -> ConsoleLevel.ERROR
        "WARNING" -> ConsoleLevel.WARN
        "TIP" -> ConsoleLevel.INFO
        "DEBUG" -> ConsoleLevel.DEBUG
        else -> ConsoleLevel.LOG
    }

    /** `[almus:pick] path:line tag`, or null when the message is ordinary console output. */
    fun parsePick(message: String): PickEvent? {
        if (!message.startsWith("[almus:pick] ")) return null
        val rest = message.removePrefix("[almus:pick] ").trim()
        val sp = rest.lastIndexOf(' ')
        if (sp <= 0) return null
        val loc = rest.substring(0, sp)
        val tag = rest.substring(sp + 1)
        val colon = loc.lastIndexOf(':')
        if (colon <= 0) return null
        val line = loc.substring(colon + 1).toIntOrNull() ?: return null
        val path = loc.substring(0, colon)
        if (path.isEmpty() || path.contains("..") || tag.isEmpty() || tag.length > 40) return null
        return PickEvent(path, line, tag)
    }

    fun fromWebView(now: Long, webViewLevel: String?, message: String?, sourceId: String?, line: Int, entry: String = "index.html"): ConsoleEntry {
        val text = SecretScanner.redact(message.orEmpty()).text.take(MAX_MESSAGE)
        val level = levelOf(webViewLevel)
        val path = sourceId?.let { PreviewRequestPolicy.pathOf(it, entry) }
        val origin = if (level == ConsoleLevel.ERROR && (text.startsWith("Uncaught") || text.contains("SyntaxError") || text.contains("ReferenceError") || text.contains("TypeError"))) ConsoleOrigin.SCRIPT_ERROR else ConsoleOrigin.PAGE
        return ConsoleEntry(now, level, origin, text, path, line.takeIf { it > 0 && path != null })
    }

    fun system(now: Long, level: ConsoleLevel, origin: ConsoleOrigin, message: String, path: String? = null, line: Int? = null): ConsoleEntry =
        ConsoleEntry(now, level, origin, SecretScanner.redact(message).text.take(MAX_MESSAGE), path, line)

    /** Appends [e]; an identical line right after the last one only raises its repeat counter. Old entries drop off. */
    fun append(list: List<ConsoleEntry>, e: ConsoleEntry): List<ConsoleEntry> {
        val last = list.lastOrNull()
        if (last != null && last.level == e.level && last.origin == e.origin && last.message == e.message && last.path == e.path && last.line == e.line) {
            return list.dropLast(1) + last.copy(repeat = last.repeat + 1, atMillis = e.atMillis)
        }
        val next = list + e
        return if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
    }

    fun filter(list: List<ConsoleEntry>, min: ConsoleLevel): List<ConsoleEntry> = list.filter { it.level.ordinal >= min.ordinal }

    fun asText(list: List<ConsoleEntry>): String = list.joinToString("\n") { e ->
        "[${e.level.name}] ${e.message}" + (e.path?.let { " ($it${e.line?.let { l -> ":$l" } ?: ""})" } ?: "") + if (e.repeat > 1) " x${e.repeat}" else ""
    }
}

/** Responsive helpers: the width breakpoints a project's CSS reacts to, so the preview can jump to them. */
object BreakpointExtractor {
    private val query = Regex("\\(\\s*(?:min|max)-width\\s*:\\s*([0-9]*\\.?[0-9]+)\\s*(px|em|rem)\\s*\\)", RegexOption.IGNORE_CASE)

    fun extract(css: Collection<String>, limit: Int = 12): List<Int> {
        val found = java.util.TreeSet<Int>()
        for (text in css) for (m in query.findAll(text)) {
            val v = m.groupValues[1].toFloatOrNull() ?: continue
            val px = if (m.groupValues[2].equals("px", true)) v else v * 16f
            val r = px.toInt()
            if (r in DeviceCatalog.MIN_DP..DeviceCatalog.MAX_DP) found += r
        }
        return found.toList().take(limit)
    }
}
