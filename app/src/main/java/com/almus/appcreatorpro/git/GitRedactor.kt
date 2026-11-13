package com.almus.appcreatorpro.git

/** Removes anything that looks like a credential from text that may be shown or logged. */
object GitRedactor {
    private const val MASK = "***"

    private val userInfo = Regex("""(?i)([a-z][a-z0-9+.\-]*://)[^/\s@]+@""")
    private val ghToken = Regex("""\bgh[pousr]_[A-Za-z0-9]{16,}""")
    private val finePat = Regex("""\bgithub_pat_[A-Za-z0-9_]{16,}""")
    private val authHeader = Regex("""(?i)(authorization\s*[:=]\s*(?:bearer|basic|token)?\s*)[^\s,;]+""")
    private val kv = Regex("""(?i)\b(access_token|token|password|passwd|secret|client_secret)\s*=\s*[^&\s]+""")

    fun redact(text: String): String {
        var t = text
        t = userInfo.replace(t) { it.groupValues[1] + MASK + "@" }
        t = ghToken.replace(t, MASK)
        t = finePat.replace(t, MASK)
        t = authHeader.replace(t) { it.groupValues[1] + MASK }
        t = kv.replace(t) { it.groupValues[1] + "=" + MASK }
        return t
    }

    /** `https://user:pw@host/x` -> `https://host/x`. */
    fun stripUserInfo(url: String): String = userInfo.replace(url) { it.groupValues[1] }
}
