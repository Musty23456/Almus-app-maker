package com.almus.appcreatorpro.ai

data class SseEvent(val event: String, val data: String)

/** Line-based Server-Sent-Events reader: feed each line of the response; an event is returned at its blank line. */
class SseEventReader {
    private var event = "message"
    private val data = ArrayList<String>()

    fun feedLine(rawLine: String): SseEvent? {
        val line = rawLine.trimEnd('\r')
        if (line.isEmpty()) return flush()
        if (line.startsWith(":")) return null // comment / keep-alive
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> data += value
        }
        return null
    }

    /** At the end of the stream: an event that was not followed by a blank line. */
    fun finish(): SseEvent? = flush()

    private fun flush(): SseEvent? {
        if (data.isEmpty()) { event = "message"; return null }
        val out = SseEvent(event, data.joinToString("\n"))
        event = "message"
        data.clear()
        return out
    }
}
