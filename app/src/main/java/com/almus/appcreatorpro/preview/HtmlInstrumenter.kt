package com.almus.appcreatorpro.preview

data class InstrumentedHtml(val html: String, val externalRefs: List<String>, val hasViewport: Boolean)

/**
 * Prepares a COPY of an HTML file for the sandbox. It never changes the project file.
 *  - injects a Content-Security-Policy and the small theme/inspect script into the head, on the same line, so line
 *    numbers in console messages still match the source;
 *  - marks every body element with `data-almus-src="file:line"` (tap-to-source);
 *  - rewrites `prefers-color-scheme`/`color-scheme` for the chosen mode;
 *  - removes `<base>` (it could re-point relative URLs) and reports external resources.
 * The scanner is tolerant (broken HTML must still preview) and understands comments and raw-text script/style content.
 */
object HtmlInstrumenter {
    private val headOnly = setOf("html", "head", "meta", "link", "title", "base", "script", "style")
    private val externalAttrs = mapOf("script" to "src", "link" to "href", "img" to "src", "iframe" to "src", "source" to "src", "video" to "src", "audio" to "src", "form" to "action", "embed" to "src", "object" to "data")

    fun csp(allowInternet: Boolean): String {
        val net = if (allowInternet) " https:" else ""
        return "default-src 'none'; script-src 'self' 'unsafe-inline' 'unsafe-eval'$net; style-src 'self' 'unsafe-inline'$net; img-src 'self' data: blob:$net; " +
            "font-src 'self' data:$net; media-src 'self' data: blob:$net; connect-src 'self'$net; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    }

    private fun attrRegex(name: String) = Regex("(?i)\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")

    fun attrValue(tag: String, name: String): String? = attrRegex(name).find(tag)?.let { m -> m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value }

    fun instrument(html: String, path: String, dark: Boolean, allowInternet: Boolean): InstrumentedHtml {
        val n = html.length
        val payload = "<meta http-equiv=\"Content-Security-Policy\" content=\"${csp(allowInternet).replace("\"", "&quot;")}\"><script>${ThemeEmulation.hookScript(dark)}</script>"

        // Where the payload goes: inside <head>, else after <html>, else after the doctype, else at the very start.
        val headStart = Regex("(?i)<head(\\s[^>]*)?>").find(html)
        val htmlStart = Regex("(?i)<html(\\s[^>]*)?>").find(html)
        val doctype = Regex("(?i)<!doctype[^>]*>").find(html)
        val insertAt: Int
        val wrap: Boolean
        when {
            headStart != null -> { insertAt = headStart.range.last + 1; wrap = false }
            htmlStart != null -> { insertAt = htmlStart.range.last + 1; wrap = true }
            doctype != null -> { insertAt = doctype.range.last + 1; wrap = true }
            else -> { insertAt = 0; wrap = true }
        }
        val injection = if (wrap) "<head>$payload</head>" else payload

        val out = StringBuilder(n + payload.length + 256)
        val external = ArrayList<String>()
        var hasViewport = false
        var injected = false
        var line = 1
        var i = 0
        if (insertAt == 0) { out.append(injection); injected = true }

        fun copy(from: Int, to: Int) {
            for (k in from until to) if (html[k] == '\n') line++
            out.append(html, from, to)
        }

        while (i < n) {
            if (!injected && i >= insertAt) { out.append(injection); injected = true }
            if (html.startsWith("<!--", i)) {
                val end = html.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                copy(i, end); i = end; continue
            }
            if (html[i] == '<' && i + 1 < n && html[i + 1].isLetter()) {
                var j = i + 1
                while (j < n && (html[j].isLetterOrDigit() || html[j] == '-' || html[j] == ':')) j++
                val name = html.substring(i + 1, j).lowercase()
                var quote = '\u0000'
                var k = j
                while (k < n) {
                    val c = html[k]
                    if (quote != '\u0000') { if (c == quote) quote = '\u0000' } else if (c == '"' || c == '\'') quote = c else if (c == '>') break
                    k++
                }
                if (k >= n) { copy(i, n); i = n; continue }
                val tagEnd = k + 1
                var tag = html.substring(i, tagEnd)
                val tagLine = line

                var drop = false
                if (name == "base") drop = true
                if (name == "meta") {
                    if (attrValue(tag, "name").equals("viewport", ignoreCase = true)) hasViewport = true
                    if (attrValue(tag, "name").equals("color-scheme", ignoreCase = true)) {
                        attrValue(tag, "content")?.let { c -> tag = attrRegex("content").replace(tag) { "content=\"" + ThemeEmulation.resolveScheme(c, dark) + "\"" } }
                    }
                }
                externalAttrs[name]?.let { a ->
                    val v = attrValue(tag, a)?.trim()
                    if (v != null && (v.startsWith("http://", true) || v.startsWith("https://", true) || v.startsWith("//")) && external.size < 40) external += v
                }
                if (name !in headOnly && !tag.contains("data-almus-src", ignoreCase = true)) {
                    val selfClose = tag.endsWith("/>")
                    val cut = if (selfClose) tag.length - 2 else tag.length - 1
                    tag = tag.substring(0, cut).trimEnd() + " data-almus-src=\"" + path.replace("\"", "") + ":" + tagLine + "\"" + (if (selfClose) "/>" else ">")
                }

                if (drop) { for (q in i until tagEnd) if (html[q] == '\n') line++ } else { out.append(tag); for (q in i until tagEnd) if (html[q] == '\n') line++ }
                i = tagEnd

                if (name == "script" || name == "style") {
                    val closeTag = "</$name"
                    val close = html.indexOf(closeTag, i, ignoreCase = true).let { if (it < 0) n else it }
                    val body = html.substring(i, close)
                    val written = if (name == "style") ThemeEmulation.rewriteCss(body, dark) else body
                    for (ch in body) if (ch == '\n') line++
                    out.append(written)
                    i = close
                }
                continue
            }
            val next = html.indexOf('<', i + 1).let { if (it < 0) n else it }
            copy(i, next)
            i = next
        }
        if (!injected) out.append(injection)
        return InstrumentedHtml(out.toString(), external, hasViewport)
    }
}
