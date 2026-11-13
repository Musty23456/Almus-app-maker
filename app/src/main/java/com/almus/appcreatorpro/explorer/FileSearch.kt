package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.domain.model.SourceFileEntry

/**
 * File-name search. The query is split on spaces and EVERY word must match the path. A word starting with "." or "*."
 * matches the extension (".kt", "*.xml"). Better matches (exact name, name prefix) rank first.
 */
object FileSearch {

    /** Score of [path] for [query]; -1 when it does not match, 0 for a blank query. */
    fun score(path: String, query: String): Int {
        val terms = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return 0
        val lowerPath = path.lowercase()
        val name = lowerPath.substringAfterLast('/')
        var total = 0
        for (term in terms) {
            val s = termScore(lowerPath, name, term)
            if (s < 0) return -1
            total += s
        }
        return total
    }

    private fun termScore(path: String, name: String, term: String): Int {
        if (term.startsWith(".") || term.startsWith("*.")) {
            val ext = term.removePrefix("*")
            return if (ext.length > 1 && name.endsWith(ext)) 70 else -1
        }
        return when {
            name == term -> 100
            name.startsWith(term) -> 80
            name.contains(term) -> 60
            path.contains(term) -> 40
            isSubsequence(term, name) -> 20
            isSubsequence(term, path) -> 10
            else -> -1
        }
    }

    private fun isSubsequence(term: String, text: String): Boolean {
        var i = 0
        for (c in text) {
            if (i < term.length && term[i] == c) i++
        }
        return i == term.length
    }

    /** Matching files, best first (ties by path). A blank query returns every file in path order. */
    fun search(entries: List<SourceFileEntry>, query: String, limit: Int = 500): List<SourceFileEntry> {
        if (query.isBlank()) return entries.sortedBy { it.path.lowercase() }.take(limit)
        return entries
            .map { it to score(it.path, query) }
            .filter { it.second >= 0 }
            .sortedWith(compareByDescending<Pair<SourceFileEntry, Int>> { it.second }.thenBy { it.first.path.lowercase() })
            .map { it.first }
            .take(limit)
    }
}
