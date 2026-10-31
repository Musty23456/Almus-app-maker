package com.almus.appcreatorpro.editor

object FindEngine {
    private const val MAX_MATCHES = 10_000

    /** Non-overlapping matches in order. An empty query matches nothing. Capped at [MAX_MATCHES]. */
    fun findAll(text: String, query: String, caseSensitive: Boolean): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val result = ArrayList<IntRange>()
        var from = 0
        while (result.size < MAX_MATCHES) {
            val index = text.indexOf(query, from, ignoreCase = !caseSensitive)
            if (index < 0) break
            result += index until index + query.length
            from = index + query.length
        }
        return result
    }

    /** Index of the first match starting at or after [offset]; wraps to 0. Requires non-empty [matches]. */
    fun nextIndex(matches: List<IntRange>, offset: Int): Int {
        val i = matches.indexOfFirst { it.first >= offset }
        return if (i < 0) 0 else i
    }

    /** Index of the last match starting before [offset]; wraps to the last match. Requires non-empty [matches]. */
    fun previousIndex(matches: List<IntRange>, offset: Int): Int {
        val i = matches.indexOfLast { it.first < offset }
        return if (i < 0) matches.lastIndex else i
    }

    /** Replaces every match (no cap). Returns the new text and the number of replacements. */
    fun replaceAll(text: String, query: String, replacement: String, caseSensitive: Boolean): Pair<String, Int> {
        if (query.isEmpty()) return text to 0
        val sb = StringBuilder()
        var from = 0
        var count = 0
        while (true) {
            val index = text.indexOf(query, from, ignoreCase = !caseSensitive)
            if (index < 0) break
            sb.append(text, from, index).append(replacement)
            from = index + query.length
            count++
        }
        sb.append(text, from, text.length)
        return sb.toString() to count
    }
}
