package com.almus.appcreatorpro.debug

/**
 * Keeps a very large build log from overwhelming the phone. A failed Gradle build prints its errors at the END, so when
 * the log is too long the tail is kept; and a single endless line (a minified file, a printed class path) is cut,
 * because analysis and redaction cost grows with line length.
 */
object LogLimits {
    const val MAX_LOG_CHARS = 8_000_000
    const val MAX_LINE_CHARS = 20_000

    /** [truncated] is true when the beginning of the log was dropped (an earlier error may be missing). */
    data class Bounded(val text: String, val note: String?, val truncated: Boolean = false)

    fun bound(text: String): Bounded {
        var t = text
        var truncated = false
        val notes = ArrayList<String>()
        if (t.length > MAX_LOG_CHARS) {
            t = t.substring(t.length - MAX_LOG_CHARS)
            truncated = true
            val firstBreak = t.indexOf('\n')
            if (firstBreak in 0 until t.length - 1) t = t.substring(firstBreak + 1) // drop the line that was cut in half
            notes += "This log is very large, so only the last ${t.length / 1_000_000} MB was analysed."
        }
        if (hasLongLine(t)) {
            var cut = 0
            t = buildString(t.length) {
                var first = true
                for (line in t.split('\n')) {
                    if (!first) append('\n')
                    first = false
                    if (line.length > MAX_LINE_CHARS) { append(line, 0, MAX_LINE_CHARS).append(" ...[line cut]"); cut++ } else append(line)
                }
            }
            notes += "$cut very long line(s) were shortened."
        }
        return Bounded(t, notes.joinToString(" ").ifBlank { null }, truncated)
    }

    private fun hasLongLine(t: String): Boolean {
        var run = 0
        for (c in t) {
            if (c == '\n') run = 0 else if (++run > MAX_LINE_CHARS) return true
        }
        return false
    }
}
