package com.almus.appcreatorpro.editor

import java.util.regex.PatternSyntaxException

data class SearchOptions(val caseSensitive: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)

/** [line] and [column] are 1-based. [lineText] is the line (trimmed to a readable length), [length] is the match length. */
data class SearchHit(val path: String, val line: Int, val column: Int, val length: Int, val lineText: String)

data class SearchOutcome(val hits: List<SearchHit>, val filesSearched: Int, val truncated: Boolean, val error: String? = null)

data class ReplaceOutcome(val changed: Map<String, String>, val replacements: Int, val error: String? = null)

/**
 * Phase 11: search and replace across the files of a project. Works on text already loaded by the caller (a path -> content
 * map), so it never touches storage. Each line is searched on its own, so a regular expression cannot match across lines.
 * Invalid regular expressions give an error message, never an exception. Very long lines (minified files) are searched in
 * their first [MAX_LINE_CHARS] characters only, and files that look binary are skipped.
 */
object ProjectSearch {
    const val MAX_HITS = 500
    const val MAX_PER_FILE = 100
    const val MAX_LINE_CHARS = 2000
    private const val PREVIEW_CHARS = 200

    private fun compile(query: String, options: SearchOptions): Pair<Regex?, String?> {
        val body = if (options.regex) query else Regex.escape(query)
        val wrapped = if (options.wholeWord) "(?<![\\w\$])(?:$body)(?![\\w\$])" else body
        return try {
            Pair(if (options.caseSensitive) Regex(wrapped) else Regex(wrapped, RegexOption.IGNORE_CASE), null)
        } catch (e: PatternSyntaxException) {
            Pair(null, "Invalid regular expression: " + (e.description ?: "syntax error"))
        } catch (e: IllegalArgumentException) {
            Pair(null, "Invalid regular expression")
        }
    }

    private fun looksBinary(text: String): Boolean = text.indexOf('\u0000') >= 0

    fun search(files: Map<String, String>, query: String, options: SearchOptions = SearchOptions(), maxHits: Int = MAX_HITS): SearchOutcome {
        if (query.isEmpty()) return SearchOutcome(emptyList(), 0, false)
        val (regex, error) = compile(query, options)
        if (regex == null) return SearchOutcome(emptyList(), 0, false, error)
        val hits = ArrayList<SearchHit>()
        var searched = 0
        var truncated = false
        for (path in files.keys.sorted()) {
            val text = files[path] ?: continue
            if (looksBinary(text)) continue
            searched++
            var inFile = 0
            var lineNo = 0
            for (rawLine in text.split('\n')) {
                lineNo++
                val line = rawLine.trimEnd('\r')
                val scanned = if (line.length > MAX_LINE_CHARS) line.substring(0, MAX_LINE_CHARS) else line
                for (m in regex.findAll(scanned)) {
                    if (m.value.isEmpty()) continue
                    if (hits.size >= maxHits || inFile >= MAX_PER_FILE) {
                        truncated = true
                        break
                    }
                    hits.add(SearchHit(path, lineNo, m.range.first + 1, m.value.length, preview(line)))
                    inFile++
                }
                if (truncated && hits.size >= maxHits) return SearchOutcome(hits, searched, true)
                if (inFile >= MAX_PER_FILE) break
            }
        }
        return SearchOutcome(hits, searched, truncated)
    }

    private fun preview(line: String): String {
        val t = line.trim()
        return if (t.length > PREVIEW_CHARS) t.substring(0, PREVIEW_CHARS) + "…" else t
    }

    /**
     * Replaces every match in the given files ([onlyPaths] null = all). Returns only the files whose content changed. In
     * regex mode the replacement may use group references like $1; otherwise it is taken literally.
     */
    fun replaceAll(
        files: Map<String, String>,
        query: String,
        replacement: String,
        options: SearchOptions = SearchOptions(),
        onlyPaths: Set<String>? = null,
    ): ReplaceOutcome {
        if (query.isEmpty()) return ReplaceOutcome(emptyMap(), 0)
        val (regex, error) = compile(query, options)
        if (regex == null) return ReplaceOutcome(emptyMap(), 0, error)
        val changed = LinkedHashMap<String, String>()
        var total = 0
        for (path in files.keys.sorted()) {
            if (onlyPaths != null && path !in onlyPaths) continue
            val text = files[path] ?: continue
            if (looksBinary(text)) continue
            val lines = text.split('\n')
            var fileCount = 0
            val rebuilt = lines.map { raw ->
                val cr = raw.endsWith("\r")
                val line = if (cr) raw.dropLast(1) else raw
                var count = 0
                val replaced = regex.replace(line) { m ->
                    if (m.value.isEmpty()) {
                        m.value
                    } else {
                        count++
                        if (options.regex) expand(m, replacement) else replacement
                    }
                }
                fileCount += count
                if (cr) replaced + "\r" else replaced
            }
            if (fileCount > 0) {
                changed[path] = rebuilt.joinToString("\n")
                total += fileCount
            }
        }
        return ReplaceOutcome(changed, total)
    }

    /** Expands $1 style group references; an unknown group becomes empty and "\$" is a literal dollar sign. */
    private fun expand(m: MatchResult, replacement: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val c = replacement[i]
            if (c == '\\' && i + 1 < replacement.length && replacement[i + 1] == '$') {
                sb.append('$')
                i += 2
            } else if (c == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit()) {
                val g = replacement[i + 1] - '0'
                sb.append(if (g < m.groups.size) m.groups[g]?.value.orEmpty() else "")
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
