package com.almus.appcreatorpro.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSandboxTest {
    private val page = """<!DOCTYPE html>
<html>
<head>
<title>T</title>
<link rel="stylesheet" href="style.css">
<style>@media (prefers-color-scheme: dark){body{color:#fff}}</style>
</head>
<body>
<h1 id="a">Hi</h1>
<script>console.log(1)</script>
</body>
</html>"""

    // ---- theme emulation

    @Test fun `dark mode turns dark media queries on and light ones off`() {
        val css = "@media (prefers-color-scheme: dark) { a{} } @media ( prefers-color-scheme : light ) and (min-width: 600px) { b{} }"
        val dark = ThemeEmulation.rewriteCss(css, true)
        assertFalse(dark.contains("prefers-color-scheme"))
        assertTrue(dark.contains("@media (min-width: 0px) { a{} }"))
        assertTrue(dark.contains("(max-width: 0px) and (min-width: 600px)"))
        val light = ThemeEmulation.rewriteCss(css, false)
        assertTrue(light.contains("@media (max-width: 0px) { a{} }"))
        assertTrue(light.contains("(min-width: 0px) and (min-width: 600px)"))
    }

    @Test fun `color-scheme resolves to the mode and keeps a single supported scheme`() {
        assertEquals("dark", ThemeEmulation.resolveScheme("light dark", true))
        assertEquals("light", ThemeEmulation.resolveScheme("light dark", false))
        assertEquals("dark", ThemeEmulation.resolveScheme("dark", false))
        assertEquals("light", ThemeEmulation.resolveScheme("normal", true))
        assertTrue(ThemeEmulation.rewriteCss(":root{color-scheme: light dark;}", true).contains("color-scheme: dark"))
        assertTrue(ThemeEmulation.rewriteCss(":root{color-scheme:dark}", false).contains("color-scheme: dark"))
    }

    @Test fun `the hook script is one line, matches the mode, and removes WebRTC`() {
        val dark = ThemeEmulation.hookScript(true)
        assertFalse(dark.contains('\n'))
        assertTrue(dark.contains("var D=true"))
        assertTrue(ThemeEmulation.hookScript(false).contains("var D=false"))
        assertTrue(dark.contains("RTCPeerConnection"))
        assertTrue(dark.contains("[almus:pick]"))
        assertFalse(dark.contains("addJavascriptInterface"))
    }

    // ---- html instrumentation

    @Test fun `the policy and script go right after head and line numbers do not move`() {
        val r = HtmlInstrumenter.instrument(page, "index.html", dark = false, allowInternet = false)
        assertTrue(r.html.contains("<head><meta http-equiv=\"Content-Security-Policy\""))
        assertEquals(page.count { it == '\n' }, r.html.count { it == '\n' })
        assertEquals(1, Regex("Content-Security-Policy").findAll(r.html).count())
        assertFalse(r.hasViewport)
    }

    @Test fun `body elements get source markers, head elements do not`() {
        val r = HtmlInstrumenter.instrument(page, "index.html", false, false)
        assertTrue(r.html.contains("<h1 id=\"a\" data-almus-src=\"index.html:9\">"))
        assertFalse(r.html.contains("<title data-almus-src"))
        assertFalse(r.html.contains("<style data-almus-src"))
        assertFalse(r.html.contains("<script data-almus-src"))
        assertFalse(r.html.contains("<body>"))
        assertTrue(r.html.contains("<body data-almus-src=\"index.html:8\">"))
    }

    @Test fun `inline style rules follow the chosen mode`() {
        assertTrue(HtmlInstrumenter.instrument(page, "index.html", true, false).html.contains("@media (min-width: 0px){body{color:#fff}}"))
        assertTrue(HtmlInstrumenter.instrument(page, "index.html", false, false).html.contains("@media (max-width: 0px){body{color:#fff}}"))
    }

    @Test fun `base is removed and external resources are reported`() {
        val r = HtmlInstrumenter.instrument("<html><head><base href=\"http://evil.example/\"><script src=\"https://cdn.example/a.js\"></script><link href=\"//cdn.example/s.css\" rel=\"stylesheet\"></head><body><img src=\"local.png\"><a href=\"https://example.com\">x</a></body></html>", "i.html", false, false)
        assertFalse(r.html.contains("<base"))
        assertEquals(listOf("https://cdn.example/a.js", "//cdn.example/s.css"), r.externalRefs)
    }

    @Test fun `a page without a head gets one, a fragment starts with it, and the doctype stays first`() {
        val noHead = HtmlInstrumenter.instrument("<html><body><p>x</p></body></html>", "a.html", false, false).html
        assertTrue(noHead.startsWith("<html><head><meta http-equiv"))
        assertEquals(1, Regex("<head>").findAll(noHead).count())
        val fragment = HtmlInstrumenter.instrument("<p>x</p>", "a.html", false, false).html
        assertTrue(fragment.startsWith("<head><meta http-equiv"))
        val doctypeOnly = HtmlInstrumenter.instrument("<!doctype html><p>x</p>", "a.html", false, false).html
        assertTrue(doctypeOnly.startsWith("<!doctype html><head>"))
    }

    @Test fun `comments, quoted greater-than signs and self-closing tags are handled`() {
        val r = HtmlInstrumenter.instrument("<html><head></head><body><!-- <h1>no</h1> --><div title=\"a>b\">x</div><img src=\"a.png\"/><br></body></html>", "i.html", false, false).html
        assertTrue(r.contains("<!-- <h1>no</h1> -->"))
        assertTrue(r.contains("title=\"a>b\" data-almus-src=\"i.html:1\">"))
        assertTrue(r.contains("<img src=\"a.png\" data-almus-src=\"i.html:1\"/>"))
        assertTrue(r.contains("<br data-almus-src=\"i.html:1\">"))
    }

    @Test fun `viewport and color-scheme meta tags are understood`() {
        val html = "<html><head><meta name=\"viewport\" content=\"width=device-width\"><meta name=\"color-scheme\" content=\"light dark\"></head><body></body></html>"
        val dark = HtmlInstrumenter.instrument(html, "i.html", true, false)
        assertTrue(dark.hasViewport)
        assertTrue(dark.html.contains("content=\"dark\""))
        assertTrue(HtmlInstrumenter.instrument(html, "i.html", false, false).html.contains("content=\"light\""))
    }

    @Test fun `broken html still previews and an unfinished tag does not loop forever`() {
        assertNotNull(HtmlInstrumenter.instrument("<div <p> <span", "b.html", false, false).html)
        assertNotNull(HtmlInstrumenter.instrument("<script>var a = '</', '</p>'", "b.html", false, false).html)
        assertNotNull(HtmlInstrumenter.instrument("", "b.html", false, false).html)
    }

    @Test fun `the policy only allows the internet tokens when asked`() {
        assertFalse(HtmlInstrumenter.csp(false).contains("https:"))
        assertTrue(HtmlInstrumenter.csp(true).contains(" https:"))
        for (csp in listOf(HtmlInstrumenter.csp(false), HtmlInstrumenter.csp(true))) {
            assertTrue(csp.contains("default-src 'none'"))
            assertTrue(csp.contains("frame-src 'none'"))
            assertTrue(csp.contains("form-action 'none'"))
            assertTrue(csp.contains("object-src 'none'"))
            assertTrue(csp.contains("base-uri 'none'"))
        }
    }

    // ---- request policy

    private val files = setOf("index.html", "css/a.css", "docs/index.html", "my page.html", "app.js")
    private fun decide(url: String, method: String = "GET", internet: Boolean = false, main: Boolean = false) = PreviewRequestPolicy.decide(url, method, files, "index.html", internet, main)
    private fun blocked(d: PreviewRequestPolicy.Decision) = d is PreviewRequestPolicy.Decision.Block

    @Test fun `project files are served from the virtual origin`() {
        assertEquals(PreviewRequestPolicy.Decision.Serve("index.html"), decide("https://preview.almus.invalid/"))
        assertEquals(PreviewRequestPolicy.Decision.Serve("css/a.css"), decide("https://preview.almus.invalid/css/a.css?v=3#x"))
        assertEquals(PreviewRequestPolicy.Decision.Serve("docs/index.html"), decide("https://preview.almus.invalid/docs/"))
        assertEquals(PreviewRequestPolicy.Decision.Serve("my page.html"), decide("https://preview.almus.invalid/my%20page.html"))
        assertEquals(PreviewRequestPolicy.Decision.Serve("index.html"), decide("https://PREVIEW.almus.invalid/index.html"))
        assertEquals("css/a.css", PreviewRequestPolicy.pathOf("https://preview.almus.invalid/css/a.css"))
        assertNull(PreviewRequestPolicy.pathOf("https://evil.com/css/a.css"))
    }

    @Test fun `missing files are a 404 and traversal is refused`() {
        val missing = decide("https://preview.almus.invalid/nope.js") as PreviewRequestPolicy.Decision.Block
        assertEquals(404, missing.status)
        assertTrue(blocked(decide("https://preview.almus.invalid/../secret")))
        assertTrue(blocked(decide("https://preview.almus.invalid/%2e%2e/secret")))
        assertTrue(blocked(decide("https://preview.almus.invalid/a%2F..%2F..%2Fb")) || decide("https://preview.almus.invalid/a%2F..%2F..%2Fb") is PreviewRequestPolicy.Decision.Block)
        assertTrue(blocked(decide("https://preview.almus.invalid/a\\b")))
        assertTrue(blocked(decide("https://preview.almus.invalid/%zz")))
        assertTrue(blocked(decide("https://preview.almus.invalid/app.js%00")))
    }

    @Test fun `only GET and HEAD work, even on the virtual origin`() {
        assertEquals(405, (decide("https://preview.almus.invalid/index.html", "POST") as PreviewRequestPolicy.Decision.Block).status)
        assertEquals(PreviewRequestPolicy.Decision.Serve("app.js"), decide("https://preview.almus.invalid/app.js", "HEAD"))
    }

    @Test fun `every other scheme is blocked`() {
        for (u in listOf("file:///data/data/com.almus/files/x", "content://media/external/1", "intent://x#Intent;end", "javascript:alert(1)", "ftp://example.com/a", "chrome://version", "view-source:https://example.com", "blob:https://x/1".replace("blob", "filesystem"))) {
            assertTrue(u, blocked(decide(u, internet = true)))
        }
        assertTrue(blocked(decide("not a url")))
    }

    @Test fun `data and blob subresources are fine but never the top-level page`() {
        assertEquals(PreviewRequestPolicy.Decision.Inline, decide("data:image/png;base64,AAAA"))
        assertEquals(PreviewRequestPolicy.Decision.Inline, decide("blob:https://preview.almus.invalid/uuid"))
        assertEquals(PreviewRequestPolicy.Decision.Inline, decide("about:blank", main = true))
        assertTrue(blocked(decide("data:text/html,<script>1</script>", main = true)))
    }

    @Test fun `with the internet off nothing outside the project is reachable`() {
        for (u in listOf("https://example.com/a.js", "http://example.com/", "https://192.168.1.1/", "https://127.0.0.1:8080/", "https://[::1]/", "https://localhost/")) {
            assertTrue(u, blocked(decide(u)))
        }
    }

    @Test fun `with the internet on only public https names on the standard port are allowed`() {
        assertEquals(PreviewRequestPolicy.Decision.External, decide("https://example.com/a.js", internet = true))
        assertEquals(PreviewRequestPolicy.Decision.External, decide("https://cdn.example.org:443/x", internet = true))
        for (u in listOf(
            "http://example.com/", "https://192.168.1.1/", "https://10.0.0.5/", "https://127.0.0.1/", "https://169.254.169.254/latest/meta-data", "https://[::1]/", "https://[fe80::1]/",
            "https://2130706433/", "https://0x7f.1/", "https://017700000001/", "https://localhost/", "https://app.localhost/", "https://printer.local/", "https://router.lan/",
            "https://intranet/", "https://example.com:8443/", "https://user:pw@example.com/", "https://host.internal/", "https://x.invalid/",
        )) assertTrue(u, blocked(decide(u, internet = true)))
        assertTrue(blocked(decide("https://example.com/", "POST", internet = true)))
    }

    @Test fun `a lookalike of the virtual host is not served`() {
        val d = decide("https://preview.almus.invalid.evil.com/index.html")
        assertTrue(d is PreviewRequestPolicy.Decision.Block)
        assertFalse(decide("https://preview.almus.invalid.evil.com/index.html", internet = true) is PreviewRequestPolicy.Decision.Serve)
        assertTrue(blocked(decide("https://user@preview.almus.invalid/index.html")))
        assertTrue(blocked(decide("https://preview.almus.invalid:8443/index.html")))
        assertTrue(blocked(decide("http://preview.almus.invalid/index.html")))
    }

    @Test fun `forbidden host rules`() {
        assertFalse(PreviewRequestPolicy.isForbiddenHost("example.com"))
        assertFalse(PreviewRequestPolicy.isForbiddenHost("fonts.googleapis.com"))
        assertTrue(PreviewRequestPolicy.isForbiddenHost(""))
        assertTrue(PreviewRequestPolicy.isForbiddenHost("EXAMPLE"))
        assertTrue(PreviewRequestPolicy.isForbiddenHost("1.2.3.4"))
    }

    // ---- console

    @Test fun `pick messages are parsed strictly`() {
        assertEquals(PickEvent("css/a.html", 12, "div"), PreviewConsoleLog.parsePick("[almus:pick] css/a.html:12 div"))
        assertNull(PreviewConsoleLog.parsePick("hello"))
        assertNull(PreviewConsoleLog.parsePick("[almus:pick] x:y div"))
        assertNull(PreviewConsoleLog.parsePick("[almus:pick] ../x.html:1 div"))
        assertNull(PreviewConsoleLog.parsePick("[almus:pick] nolinehere"))
    }

    @Test fun `webview console messages become entries with a project path`() {
        val e = PreviewConsoleLog.fromWebView(5L, "ERROR", "Uncaught ReferenceError: x is not defined", "https://preview.almus.invalid/js/app.js", 7)
        assertEquals(ConsoleLevel.ERROR, e.level)
        assertEquals(ConsoleOrigin.SCRIPT_ERROR, e.origin)
        assertEquals("js/app.js", e.path)
        assertEquals(7, e.line)
        val plain = PreviewConsoleLog.fromWebView(5L, "LOG", "hi", "https://other.example/a.js", 3)
        assertEquals(ConsoleOrigin.PAGE, plain.origin)
        assertNull(plain.path)
        assertNull(plain.line)
        assertEquals(ConsoleLevel.WARN, PreviewConsoleLog.levelOf("WARNING"))
        assertEquals(ConsoleLevel.LOG, PreviewConsoleLog.levelOf(null))
    }

    @Test fun `secrets are removed and long messages are cut`() {
        val e = PreviewConsoleLog.fromWebView(1L, "LOG", "key AIzaSyA1234567890abcdefghijklmnopqrstuv " + "x".repeat(2000), null, 0)
        assertFalse(e.message.contains("AIzaSyA1234567890"))
        assertTrue(e.message.length <= PreviewConsoleLog.MAX_MESSAGE)
    }

    @Test fun `identical lines collapse, the log is capped, and filtering works`() {
        var log = emptyList<ConsoleEntry>()
        val a = ConsoleEntry(1, ConsoleLevel.LOG, ConsoleOrigin.PAGE, "same")
        log = PreviewConsoleLog.append(log, a)
        log = PreviewConsoleLog.append(log, a.copy(atMillis = 2))
        assertEquals(1, log.size)
        assertEquals(2, log[0].repeat)
        for (i in 1..600) log = PreviewConsoleLog.append(log, ConsoleEntry(i.toLong(), ConsoleLevel.WARN, ConsoleOrigin.PAGE, "m$i"))
        assertEquals(PreviewConsoleLog.MAX_ENTRIES, log.size)
        assertEquals("m600", log.last().message)
        assertEquals(PreviewConsoleLog.MAX_ENTRIES, PreviewConsoleLog.filter(log, ConsoleLevel.WARN).size)
        assertTrue(PreviewConsoleLog.filter(log, ConsoleLevel.ERROR).isEmpty())
        assertTrue(PreviewConsoleLog.asText(listOf(a.copy(repeat = 3, path = "a.js", line = 4))).contains("a.js:4"))
    }

    @Test fun `css breakpoints are collected in px, em and rem and sorted`() {
        val bps = BreakpointExtractor.extract(listOf("@media (min-width: 768px){} @media (max-width:600px){} @media (min-width: 48em){} @media (min-width: 10px){} @media (min-width: 30rem){}"))
        assertEquals(listOf(480, 600, 768), bps)
    }

    // ---- bundle

    private val project = mapOf(
        "index.html" to "<html><head><link rel=\"stylesheet\" href=\"style.css\"><script src=\"app.js\"></script></head><body><img src=\"logo.png\"><img src=\"missing.png\"><a href=\"other.html\">x</a><script src=\"https://cdn.example/x.js\"></script></body></html>",
        "style.css" to "@media (prefers-color-scheme: dark){body{background:#000}} @media (min-width: 700px){a{}}",
        "app.js" to "console.log('x')",
        "other.html" to "<p>other</p>",
    )

    @Test fun `the bundle holds instrumented html and rewritten css`() {
        val b = WebPreviewBuilder.build("index.html", project, project.keys + "logo.png", dark = true, allowInternet = false)
        assertEquals(setOf("index.html", "style.css", "app.js", "other.html"), b.files.keys)
        assertEquals("text/html", b.files.getValue("index.html").mime)
        assertTrue(b.files.getValue("index.html").text.contains("Content-Security-Policy"))
        assertTrue(b.files.getValue("style.css").text.contains("(min-width: 0px)"))
        assertEquals("console.log('x')", b.files.getValue("app.js").text)
        assertEquals(listOf(700), b.breakpoints)
        assertEquals("index.html", b.entry)
    }

    @Test fun `the bundle explains what the preview cannot do`() {
        val w = WebPreviewBuilder.build("index.html", project, project.keys + "logo.png", false, false).warnings
        assertTrue(w.any { it.contains("viewport") })
        assertTrue(w.any { it.contains("cdn.example") && it.contains("no network access") })
        assertTrue(w.any { it.contains("logo.png") && it.contains("binary") })
        assertTrue(w.any { it.contains("missing.png") && it.contains("not found") })
        assertFalse(w.any { it.contains("other.html") })
        assertFalse(WebPreviewBuilder.build("index.html", project, project.keys, false, true).warnings.any { it.contains("no network access") })
    }

    @Test fun `a missing entry gives no files and a message`() {
        val b = WebPreviewBuilder.build("nope.html", project, project.keys, false, false)
        assertTrue(b.files.isEmpty())
        assertTrue(b.warnings.single().contains("nope.html"))
    }

    @Test fun `only text web files enter the sandbox`() {
        assertTrue(WebPreviewBuilder.isServable("index.html"))
        assertTrue(WebPreviewBuilder.isServable("css/site.css"))
        assertFalse(WebPreviewBuilder.isServable("logo.png"))
        assertFalse(WebPreviewBuilder.isServable(".env"))
        assertFalse(WebPreviewBuilder.isServable("keystore.jks"))
        assertFalse(WebPreviewBuilder.isServable("local.properties"))
        assertFalse(WebPreviewBuilder.isServable("build/out/index.html"))
        assertEquals("image/svg+xml", WebPreviewBuilder.mimeOf("a.svg"))
        assertEquals("text/javascript", WebPreviewBuilder.mimeOf("a.mjs"))
    }

    @Test fun `file count and size limits apply`() {
        val many = (1..(WebPreviewBuilder.MAX_FILES + 20)).associate { "f$it.html" to "<p>$it</p>" } + ("index.html" to "<p>x</p>")
        val built = WebPreviewBuilder.build("index.html", many, many.keys, false, false)
        assertTrue(built.files.size <= WebPreviewBuilder.MAX_FILES)
        assertTrue(built.files.containsKey("index.html"))
        val big = mapOf("index.html" to "<p>x</p>", "huge.js" to "a".repeat(WebPreviewBuilder.MAX_TOTAL_CHARS + 10))
        assertFalse(WebPreviewBuilder.build("index.html", big, big.keys, false, false).files.containsKey("huge.js"))
    }
}
