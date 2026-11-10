package com.almus.appcreatorpro.preview

/**
 * The single gate every request of the sandboxed web preview goes through. The page lives on a virtual origin that
 * does not exist on the internet (`.invalid` is reserved and can never resolve); its files are served from memory.
 *
 * Default (internet OFF): ONLY that origin works. Every other URL (http, https, file, content, intent, ftp, ...) is
 * blocked, so a previewed page cannot read the phone's files, other projects, or send data anywhere.
 *
 * Internet ON (an explicit choice per session): public https hosts on the standard port are allowed. Still blocked:
 * IP addresses, localhost, single-label and private-looking names, other ports and user-info URLs, so the page cannot
 * probe the local network or the phone. NOT prevented: a public name that resolves to a private address (DNS
 * rebinding) because the app does not do its own DNS. That is why internet access is off by default.
 */
object PreviewRequestPolicy {
    const val HOST = "preview.almus.invalid"
    const val ORIGIN = "https://$HOST"
    const val BASE_URL = "$ORIGIN/"

    sealed interface Decision {
        data class Serve(val path: String) : Decision
        /** data:, blob: and about:blank subresources. */
        data object Inline : Decision
        data object External : Decision
        data class Block(val reason: String, val status: Int = 403) : Decision
    }

    private class Parsed(val scheme: String, val userInfo: Boolean, val host: String, val port: Int?, val path: String)

    private fun parse(url: String): Parsed? {
        val u = url.trim()
        val colon = u.indexOf(':')
        if (colon <= 0) return null
        val scheme = u.substring(0, colon).lowercase()
        if (!u.startsWith("//", colon + 1)) return Parsed(scheme, false, "", null, "")
        val rest = u.substring(colon + 3)
        val endAuth = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        var auth = rest.substring(0, endAuth)
        val path = rest.substring(endAuth).substringBefore('#').substringBefore('?')
        val userInfo = auth.contains('@')
        auth = auth.substringAfterLast('@')
        var host = auth
        var port: Int? = null
        if (!auth.startsWith("[")) {
            val pc = auth.lastIndexOf(':')
            if (pc >= 0) { host = auth.substring(0, pc); port = auth.substring(pc + 1).toIntOrNull() ?: -1 }
        }
        return Parsed(scheme, userInfo, host.lowercase().trimEnd('.'), port, path)
    }

    /** The project path a virtual URL stands for, or null when it is not one of ours or is unsafe. */
    fun pathOf(url: String, entry: String = "index.html"): String? {
        val p = parse(url) ?: return null
        if (p.scheme != "https" || p.host != HOST || p.userInfo) return null
        return normalizePath(p.path, entry)
    }

    fun normalizePath(rawPath: String, entry: String): String? {
        val decoded = percentDecode(rawPath) ?: return null
        if (decoded.indexOf('\u0000') >= 0 || decoded.contains('\\')) return null
        val parts = ArrayList<String>()
        for (seg in decoded.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> return null
                else -> parts += seg
            }
        }
        if (parts.isEmpty()) return entry
        return parts.joinToString("/")
    }

    private fun percentDecode(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return try { String(bytes.toByteArray(), Charsets.UTF_8) } catch (e: Exception) { null }
    }

    fun isForbiddenHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty()) return true
        if (h.contains(':') || h.startsWith("[")) return true                        // IPv6 literal
        if (h.all { it.isDigit() || it == '.' }) return true                          // IPv4, decimal or dotted
        if (h.startsWith("0x") || h.split('.').any { it.startsWith("0x") }) return true // hex IP
        if (!h.contains('.')) return true                                             // single label: intranet name
        val blockedSuffixes = listOf(".localhost", ".local", ".internal", ".lan", ".home", ".corp", ".intranet", ".invalid", ".test", ".example")
        if (h == "localhost" || blockedSuffixes.any { h.endsWith(it) }) return true
        return false
    }

    /**
     * @param files the project paths that can be served (the bundle's keys).
     * @param isMainFrame true for the top-level navigation of the preview.
     */
    fun decide(url: String, method: String, files: Set<String>, entry: String, allowInternet: Boolean, isMainFrame: Boolean): Decision {
        val p = parse(url) ?: return Decision.Block("Blocked an unreadable address.")
        if (p.scheme == "data" || p.scheme == "blob" || (p.scheme == "about" && url.trim().equals("about:blank", ignoreCase = true))) {
            return if (isMainFrame && p.scheme != "about") Decision.Block("A preview cannot navigate to a ${p.scheme}: address.") else Decision.Inline
        }
        if (p.scheme != "https" && p.scheme != "http") return Decision.Block("The ${p.scheme}: scheme is blocked in previews (no files, apps or other protocols).")
        if (p.host == HOST && p.scheme == "https" && !p.userInfo && (p.port == null || p.port == 443)) {
            if (!method.equals("GET", true) && !method.equals("HEAD", true)) return Decision.Block("Only GET requests are possible in a preview ($method was blocked).", 405)
            val path = normalizePath(p.path, entry) ?: return Decision.Block("Blocked an unsafe path.")
            if (path in files) return Decision.Serve(path)
            val dirIndex = if (path.endsWith("/")) path + "index.html" else "$path/index.html"
            if (dirIndex in files) return Decision.Serve(dirIndex)
            return Decision.Block("Not found in the project: $path", 404)
        }
        if (!allowInternet) return Decision.Block("Network access is off for previews. Blocked: ${p.host.ifEmpty { url.take(60) }}")
        if (p.scheme != "https") return Decision.Block("Only https is allowed when the internet is on. Blocked: $url".take(200))
        if (p.userInfo) return Decision.Block("Addresses with a user name or password are blocked.")
        if (p.port != null && p.port != 443) return Decision.Block("Only the standard https port is allowed. Blocked port ${p.port}.")
        if (isForbiddenHost(p.host)) return Decision.Block("This host looks like a local or private address and is always blocked: ${p.host}")
        if (!method.equals("GET", true) && !method.equals("HEAD", true)) return Decision.Block("Only GET requests are allowed to the internet from a preview.", 405)
        return Decision.External
    }
}
