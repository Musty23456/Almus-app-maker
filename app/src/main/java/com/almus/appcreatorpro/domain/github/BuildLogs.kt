package com.almus.appcreatorpro.domain.github

/**
 * Removes anything that looks like a secret from text before the app shows, stores or copies it.
 * GitHub already masks the secrets it knows about; this is a second safety net for tokens, passwords, keys
 * and pre-signed URL signatures that might appear in a build log.
 */
object LogRedactor {
    private val patterns: List<Pair<Regex, String>> = listOf(
        Regex("gh[pousr]_[A-Za-z0-9]{20,}") to "***",
        Regex("github_pat_[A-Za-z0-9_]{20,}") to "***",
        Regex("Bearer\\s+[A-Za-z0-9._~+/=-]{12,}", RegexOption.IGNORE_CASE) to "Bearer ***",
        Regex("(Authorization\\s*[:=]\\s*)\\S+(\\s+\\S+)?", RegexOption.IGNORE_CASE) to "\$1***",
        // NAME=value / NAME: value where NAME looks like a secret (GITHUB_TOKEN, db_password, API_KEY, PRIVATE_KEY ...).
        Regex("(\\b[A-Za-z0-9_.-]*(?:token|secret|passwd|password|api_?key|private_?key|credential)[A-Za-z0-9_.-]*\\s*[=:]\\s*)(?!\\*\\*\\*)\\S+", RegexOption.IGNORE_CASE) to "\$1***",
        // Signed-URL query parameters (storage links): sig=..., X-Amz-Signature=..., access_token=...
        Regex("((?:[?&]|\\b)(?:sig|signature|x-amz-signature|x-amz-credential|access_token|sv|se|sp|skoid|sktid)=)[^&\\s\"']+", RegexOption.IGNORE_CASE) to "\$1***",
    )

    fun redact(text: String): String {
        var out = text
        for ((regex, replacement) in patterns) out = regex.replace(out, replacement)
        return out
    }
}

/** Turns a raw Gradle/Kotlin/Java/AAPT log into a short, readable list of what went wrong. */
object GradleErrors {
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val timestamp = Regex("^\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z\\s?")
    private val kotlinLocated = Regex("^e: (?:file://)?(\\S.*?\\.kts?):(\\d+)(?::(\\d+))?:?\\s+(.*)$")
    private val kotlinPlain = Regex("^e: (.+)$")
    private val javaLocated = Regex("^(?:/\\S*?|\\S*?)([^/\\s]+\\.java):(\\d+): error: (.*)$")
    private val taskFailed = Regex("^\\s*> Task (\\S+) FAILED\\s*$")
    private val wentWrong = Regex("^\\s*\\* What went wrong:\\s*$")
    private val sectionStart = Regex("^\\s*\\* (Try|Exception is|Get more help|What went wrong)")
    private val generic = Regex("(\\berror:|Could not (?:resolve|find|get|determine)|SDK location not found|Unresolved reference|Execution failed)", RegexOption.IGNORE_CASE)

    /** The most useful problems first (compiler errors, then the Gradle summary). At most [max] lines. */
    fun extract(log: String, max: Int = 25): List<String> {
        val lines = log.lineSequence().map { clean(it) }.toList()
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < lines.size && out.size < max * 2) {
            val line = lines[i]
            when {
                line.isBlank() -> Unit
                kotlinLocated.matches(line) -> {
                    val m = kotlinLocated.matchEntire(line)!!
                    val col = m.groupValues[3].let { if (it.isEmpty()) "" else ":$it" }
                    out += "${shortPath(m.groupValues[1])}:${m.groupValues[2]}$col  ${m.groupValues[4].trim()}"
                }
                javaLocated.matches(line) -> {
                    val m = javaLocated.matchEntire(line)!!
                    out += "${m.groupValues[1]}:${m.groupValues[2]}  ${m.groupValues[3].trim()}"
                }
                kotlinPlain.matches(line) -> out += kotlinPlain.matchEntire(line)!!.groupValues[1].trim()
                taskFailed.matches(line) -> out += "Task failed: ${taskFailed.matchEntire(line)!!.groupValues[1]}"
                wentWrong.matches(line) -> {
                    var taken = 0
                    var j = i + 1
                    while (j < lines.size && taken < 4) {
                        val next = lines[j]
                        if (sectionStart.containsMatchIn(next)) break
                        if (next.isNotBlank()) {
                            out += "Gradle: ${next.trim().removePrefix("> ").trim()}"
                            taken++
                        }
                        j++
                    }
                    i = j - 1
                }
                generic.containsMatchIn(line) -> out += line.trim()
            }
            i++
        }
        return out.map { it.take(300) }.take(max)
    }

    private fun clean(raw: String): String = LogRedactor.redact(timestamp.replace(ansi.replace(raw, ""), "").trimEnd())

    private fun shortPath(path: String): String = path.split('/').filter { it.isNotEmpty() }.takeLast(2).joinToString("/")

    /** The last [maxLines] lines with colours, timestamps and secrets removed, for the "show log" view. */
    fun cleanTail(log: String, maxLines: Int = 250, maxChars: Int = 24_000): String =
        log.lineSequence().map { clean(it) }.toList().takeLast(maxLines).joinToString("\n").takeLast(maxChars)
}
