package com.almus.appcreatorpro.debug

/**
 * Finds Java/Kotlin stack traces in a build log (Gradle `--stacktrace` output, compiler crashes, plugin failures) or in
 * Logcat text, and reads them into [StackTraceAnalysis]: the chain of exceptions, the root cause (deepest "Caused by"),
 * and which frames belong to the project.
 *
 * It only reads what is printed. A frame counts as a project frame only when its file name matches exactly ONE file of
 * the project (via [ProjectPathResolver]) and the class is not part of a well-known framework; otherwise the trace is
 * reported as "framework only" instead of pointing at a guessed line.
 *
 * Linear time: every line is matched once with patterns that cannot backtrack badly, and the amount of work is capped
 * ([MAX_TRACES], [MAX_FRAMES_PER_EXCEPTION], [MAX_CHAIN]).
 */
class StackTraceAnalyzer(private val resolver: ProjectPathResolver? = null) {

    fun analyze(lines: List<LogCleaner.Line>): List<StackTraceAnalysis> {
        val texts = lines.map { stripLogcatPrefix(it.text) }
        val out = ArrayList<StackTraceAnalysis>()
        var i = 0
        while (i < texts.size && out.size < MAX_TRACES) {
            val header = parseHeader(texts[i], allowCausedBy = false)
            if (header != null && startsTrace(texts, i)) {
                val (trace, next) = readTrace(texts, i, header)
                if (trace != null) out += trace
                i = maxOf(next, i + 1)
            } else {
                i++
            }
        }
        return out
    }

    private class Builder(val type: String, var message: String?) {
        val frames = ArrayList<StackFrame>()
        var omitted = 0
    }

    private fun readTrace(texts: List<String>, start: Int, first: Header): Pair<StackTraceAnalysis?, Int> {
        val chain = ArrayList<Builder>()
        var current = Builder(first.type, first.message)
        chain += current
        var i = start + 1
        var end = start
        while (i < texts.size) {
            val raw = texts[i]
            val line = raw.trim()
            val frame = parseFrame(raw)
            when {
                frame != null -> {
                    if (current.frames.size < MAX_FRAMES_PER_EXCEPTION) current.frames += frame else current.omitted++
                    end = i
                }
                line.startsWith("...") && MORE.matches(line) -> { current.omitted += MORE.matchEntire(line)!!.groupValues[1].toIntOrNull() ?: 0; end = i }
                line.startsWith("Caused by:") -> {
                    val h = parseHeader(line, allowCausedBy = true)
                    if (h == null || chain.size >= MAX_CHAIN) break
                    current = Builder(h.type, h.message)
                    chain += current
                    end = i
                }
                line.startsWith("Suppressed:") -> {
                    // A suppressed exception has its own frames; skip them so they are not read as the main trace.
                    end = i
                    i++
                    while (i < texts.size && (parseFrame(texts[i]) != null || MORE.matches(texts[i].trim()) || texts[i].trim().startsWith("Caused by:"))) { end = i; i++ }
                    continue
                }
                line.isEmpty() -> {
                    // A blank line ends the trace unless a frame / cause follows right away.
                    val next = texts.getOrNull(i + 1)?.trim().orEmpty()
                    if (!(next.startsWith("at ") || next.startsWith("Caused by:"))) break
                }
                current.frames.isEmpty() && current.message != null && !line.startsWith("at ") && i - end <= 3 -> {
                    // Multi-line message of the exception ("... Could not resolve:\n   > details").
                    if (current.message!!.length < MAX_MESSAGE) current.message = current.message + " " + line
                    end = i
                }
                else -> break
            }
            i++
        }
        if (chain.all { it.frames.isEmpty() }) return null to i
        val infos = chain.map { ExceptionInfo(it.type, it.message?.take(MAX_MESSAGE)?.let { m -> LogCleaner.redact(m) }, it.frames, it.omitted) }
        val id = Fingerprint.of("trace", start, infos.first().type, infos.last().type, infos.last().message)
        return StackTraceAnalysis(id, start, end, infos) to i
    }

    /** Is [index] the head of a real trace: followed by a frame within a few lines (and not just any "...Exception" text)? */
    private fun startsTrace(texts: List<String>, index: Int): Boolean {
        for (k in 1..4) {
            val t = texts.getOrNull(index + k) ?: return false
            if (parseFrame(t) != null) return true
            if (t.isBlank()) return false
        }
        return false
    }

    private class Header(val type: String, val message: String?)

    private fun parseHeader(text: String, allowCausedBy: Boolean): Header? {
        var s = text.trim()
        if (s.isEmpty() || s.length > MAX_HEADER) return null
        if (allowCausedBy) {
            s = s.removePrefix("Caused by:").trim()
        } else if (s.startsWith("Exception in thread \"")) {
            // Exception in thread "main" java.lang.IllegalStateException: message
            val close = s.indexOf('"', "Exception in thread \"".length)
            if (close < 0) return null
            s = s.substring(close + 1).trim()
        }
        val m = HEADER.matchEntire(s) ?: return null
        val type = m.groupValues[1]
        if (!looksLikeThrowable(type)) return null
        return Header(type, m.groupValues[2].ifEmpty { null })
    }

    private fun looksLikeThrowable(type: String): Boolean {
        val simple = type.substringAfterLast('.')
        return simple.endsWith("Exception") || simple.endsWith("Error") || simple == "Throwable" || simple.endsWith("Failure")
    }

    internal fun parseFrame(raw: String): StackFrame? {
        val m = FRAME.matchEntire(raw.trim()) ?: return null
        var cls = m.groupValues[1]
        cls = cls.substringAfter('/', cls) // "java.base/java.lang.Thread" -> "java.lang.Thread"
        val method = m.groupValues[2]
        val loc = m.groupValues[3]
        var file: String? = null
        var line: Int? = null
        val lm = LOCATION.matchEntire(loc)
        if (lm != null) {
            file = lm.groupValues[1]
            line = lm.groupValues[2].toIntOrNull()
        } else if (loc.isNotBlank() && loc != "Native Method" && loc != "Unknown Source" && loc != "SourceFile") {
            file = loc.takeIf { it.length <= 120 }
        }
        val framework = FRAMEWORK_PREFIXES.any { cls.startsWith(it) }
        val projectPath = if (!framework && file != null && (file.endsWith(".kt") || file.endsWith(".java"))) resolver?.resolve(file) else null
        return StackFrame(cls, method, file, line, projectPath, framework)
    }

    private fun stripLogcatPrefix(text: String): String {
        if (text.length < 8) return text
        LOGCAT_BRIEF.find(text)?.let { return text.substring(it.range.last + 1) }
        LOGCAT_THREADTIME.find(text)?.let { return text.substring(it.range.last + 1) }
        return text
    }

    companion object {
        const val MAX_TRACES = 20
        const val MAX_FRAMES_PER_EXCEPTION = 60
        const val MAX_CHAIN = 12
        const val MAX_MESSAGE = 600
        private const val MAX_HEADER = 2000

        private val HEADER = Regex("^((?:[A-Za-z_][\\w$]*\\.)*[A-Za-z_][\\w$]*)(?::\\s?(.*))?$")
        private val FRAME = Regex("^at ((?:[\\w$<>\\-]+[./@])*[\\w$<>\\-]+)\\.([\\w$<>\\-]+)\\(([^()]*)\\)(?:\\s.*)?$")
        private val LOCATION = Regex("^([^:()]+):(\\d+)$")
        private val MORE = Regex("^\\.\\.\\. (\\d+) (?:more|common frames omitted)$")
        private val LOGCAT_BRIEF = Regex("^[VDIWEF]/[\\w.\\-$]+\\s*\\(\\s*\\d+\\):\\s?")
        private val LOGCAT_THREADTIME = Regex("^\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d+\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+[\\w.\\-$]+\\s*:\\s?")

        /** Code of the platform and of well-known libraries: a frame here is never reported as "your code". */
        val FRAMEWORK_PREFIXES = listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "android.", "androidx.", "dalvik.", "libcore.",
            "com.android.", "com.google.", "org.gradle.", "org.jetbrains.", "org.apache.", "org.codehaus.", "groovy.", "org.slf4j.",
            "okhttp3.", "okio.", "io.netty.", "org.objectweb.", "net.rubygrapefruit.", "com.intellij.",
        )
    }
}
