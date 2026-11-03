package com.almus.appcreatorpro.debug

import com.almus.appcreatorpro.domain.github.LogRedactor

/**
 * Turns raw CI log text into clean lines. One output line per input line (blank ones included) so line indexes stay
 * the same between the log viewer, the analyzer and the exported text.
 */
object LogCleaner {
    enum class Marker { NONE, ERROR, WARNING, DEBUG, NOTICE, GROUP, END_GROUP, COMMAND }

    /** [text] is cleaned of colours, time stamps and GitHub markers but NOT redacted (see [redact]). */
    data class Line(val text: String, val timestamp: String?, val marker: Marker)

    private val ansi = Regex("\u001B\\[[0-9;?]*[A-Za-z]")
    private val timestamp = Regex("^(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z)\\s?")
    private val markers: List<Pair<String, Marker>> = listOf(
        "##[error]" to Marker.ERROR,
        "##[warning]" to Marker.WARNING,
        "##[debug]" to Marker.DEBUG,
        "##[notice]" to Marker.NOTICE,
        "##[group]" to Marker.GROUP,
        "##[endgroup]" to Marker.END_GROUP,
        "[command]" to Marker.COMMAND,
    )

    fun lines(raw: String): List<Line> {
        if (raw.isEmpty()) return emptyList()
        val text = raw.removePrefix("﻿")
        val out = ArrayList<Line>()
        for (original in text.split('\n')) {
            var s = ansi.replace(original.trimEnd('\r'), "")
            var ts: String? = null
            val tm = timestamp.find(s)
            if (tm != null) {
                ts = tm.groupValues[1]
                s = s.substring(tm.range.last + 1).let { if (it.startsWith(" ")) it.drop(1) else it }
            }
            var marker = Marker.NONE
            for ((prefix, m) in markers) {
                if (s.startsWith(prefix)) {
                    marker = m
                    s = s.removePrefix(prefix)
                    break
                }
            }
            out += Line(s.trimEnd(), ts, marker)
        }
        // A log ending with a line break would otherwise end with one phantom empty line.
        if (out.isNotEmpty() && out.last().text.isEmpty() && text.endsWith("\n")) out.removeAt(out.size - 1)
        return out
    }

    // File locations like "KeyStoreHelper.kt:12:5" must survive redaction: LogRedactor would otherwise read
    // "KeyStore...:12:5" as "name: value" and hide the line number.
    private val location = Regex("[\\w.\\-]+\\.(?:kts?|java|xml|gradle|properties|pro|toml|json):\\d+(?::\\d+)?(?:-\\d+)?")

    /** Removes secrets (tokens, passwords, signed-URL signatures) but keeps file:line locations readable. */
    fun redact(line: String): String {
        if (line.isEmpty()) return line
        val saved = ArrayList<String>()
        val protectedText = location.replace(line) { m ->
            saved += m.value
            "\u0002${saved.size - 1}\u0003"
        }
        var result = LogRedactor.redact(protectedText)
        if (saved.isNotEmpty()) {
            result = Regex("\u0002(\\d+)\u0003").replace(result) { m -> saved.getOrNull(m.groupValues[1].toInt()).orEmpty() }
        }
        return result
    }
}
