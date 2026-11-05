package com.almus.appcreatorpro.editor

object BracketMatcher {
    private const val OPENING = "([{"
    private const val CLOSING = ")]}"
    private const val MAX_SCAN = 50_000

    /**
     * If the caret is next to a bracket, returns (bracketIndex, matchingIndex); otherwise null.
     * The bracket before the caret is checked first. Brackets inside strings and comments are
     * not treated specially (kept simple on purpose).
     */
    fun findMatch(text: String, cursor: Int): Pair<Int, Int>? {
        for (index in intArrayOf(cursor - 1, cursor)) {
            if (index !in text.indices) continue
            val c = text[index]
            val open = OPENING.indexOf(c)
            if (open >= 0) {
                val match = scan(text, index, forward = true, open = c, close = CLOSING[open])
                if (match >= 0) return index to match
            }
            val close = CLOSING.indexOf(c)
            if (close >= 0) {
                val match = scan(text, index, forward = false, open = OPENING[close], close = c)
                if (match >= 0) return index to match
            }
        }
        return null
    }

    private fun scan(text: String, from: Int, forward: Boolean, open: Char, close: Char): Int {
        var depth = 0
        var i = from
        var steps = 0
        while (i in text.indices && steps <= MAX_SCAN) {
            val c = text[i]
            if (c == open) depth += if (forward) 1 else -1
            if (c == close) depth += if (forward) -1 else 1
            if (depth == 0) return i
            i += if (forward) 1 else -1
            steps++
        }
        return -1
    }
}
