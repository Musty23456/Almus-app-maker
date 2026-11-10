package com.almus.appcreatorpro.preview

class XmlElement(
    val name: String,
    val attrs: Map<String, String>,
    val children: List<XmlElement>,
    /** Direct text of the element, trimmed (what `<string>` and `<color>` hold). */
    val text: String,
    val line: Int,
    val column: Int,
) {
    fun attr(name: String): String? = attrs[name]
}

sealed interface XmlResult {
    data class Ok(val root: XmlElement) : XmlResult
    data class Error(val message: String, val line: Int) : XmlResult
}

/**
 * A small XML reader for Android resource files (layouts, values, drawables).
 *
 * It is written by hand on purpose: it never resolves entities, DTDs or external references (a `<!DOCTYPE` is refused),
 * so a malicious layout file cannot read files or reach the network, and it behaves the same on a phone and on a JVM.
 * Limits: input size, nesting depth and element count are capped. Positions (line, column) are kept for every element,
 * which lets the preview jump from a drawn view back to its source line.
 */
object SimpleXml {
    const val MAX_CHARS = 512 * 1024
    const val MAX_DEPTH = 64
    const val MAX_ELEMENTS = 5000

    fun parse(text: String): XmlResult {
        if (text.length > MAX_CHARS) return XmlResult.Error("The file is too large to preview (limit ${MAX_CHARS / 1024} KB).", 1)
        return Parser(text).run()
    }

    private class Fail(val msg: String, val line: Int) : Exception(msg)

    private class Parser(private val s: String) {
        private var i = 0
        private var line = 1
        private var lineStart = 0
        private var count = 0

        fun run(): XmlResult = try {
            skipProlog()
            if (i >= s.length) throw Fail("The file contains no element.", line)
            val root = element(0)
            skipMisc()
            if (i < s.length) throw Fail("There is more than one root element.", line)
            XmlResult.Ok(root)
        } catch (f: Fail) {
            XmlResult.Error(f.msg, f.line)
        }

        private fun advance() {
            if (s[i] == '\n') { line++; lineStart = i + 1 }
            i++
        }

        private fun startsWith(t: String) = s.startsWith(t, i)

        private fun skipTo(end: String, what: String) {
            val at = line
            while (i < s.length && !startsWith(end)) advance()
            if (i >= s.length) throw Fail("Unterminated $what.", at)
            repeat(end.length) { advance() }
        }

        private fun skipSpace() { while (i < s.length && s[i].isWhitespace()) advance() }

        private fun skipMisc() {
            while (true) {
                skipSpace()
                if (startsWith("<!--")) skipTo("-->", "comment")
                else if (startsWith("<?")) skipTo("?>", "processing instruction")
                else return
            }
        }

        private fun skipProlog() {
            skipMisc()
            if (startsWith("<!DOCTYPE") || startsWith("<!ENTITY")) throw Fail("DOCTYPE and entity declarations are not supported (they are refused for safety).", line)
        }

        private fun isNameChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == ':' || c == '.'

        private fun name(): String {
            val st = i
            while (i < s.length && isNameChar(s[i])) advance()
            if (st == i) throw Fail("A name was expected here.", line)
            return s.substring(st, i)
        }

        private fun element(depth: Int): XmlElement {
            if (depth > MAX_DEPTH) throw Fail("The layout is nested deeper than $MAX_DEPTH levels.", line)
            if (++count > MAX_ELEMENTS) throw Fail("The file has more than $MAX_ELEMENTS elements.", line)
            if (i >= s.length || s[i] != '<') throw Fail("An element was expected here.", line)
            val startLine = line
            val startCol = i - lineStart + 1
            advance()
            val tag = name()
            val attrs = LinkedHashMap<String, String>()
            while (true) {
                skipSpace()
                if (i >= s.length) throw Fail("The tag <$tag> is not closed.", startLine)
                if (startsWith("/>")) { advance(); advance(); return XmlElement(tag, attrs, emptyList(), "", startLine, startCol) }
                if (s[i] == '>') { advance(); break }
                val key = name()
                skipSpace()
                if (i >= s.length || s[i] != '=') throw Fail("Attribute $key has no value.", line)
                advance()
                skipSpace()
                if (i >= s.length || (s[i] != '"' && s[i] != '\'')) throw Fail("The value of $key must be quoted.", line)
                val quote = s[i]
                advance()
                val vs = i
                while (i < s.length && s[i] != quote) advance()
                if (i >= s.length) throw Fail("The value of $key is not closed.", line)
                attrs[key] = decode(s.substring(vs, i))
                advance()
            }
            val kids = ArrayList<XmlElement>()
            val text = StringBuilder()
            val raw = StringBuilder()
            while (true) {
                if (i >= s.length) throw Fail("The tag <$tag> is not closed.", startLine)
                if (startsWith("</")) {
                    advance(); advance()
                    val close = name()
                    skipSpace()
                    if (i >= s.length || s[i] != '>') throw Fail("The closing tag </$close> is not finished.", line)
                    advance()
                    if (close != tag) throw Fail("The closing tag </$close> does not match <$tag> from line $startLine.", line)
                    text.append(decode(raw.toString()))
                    return XmlElement(tag, attrs, kids, text.toString().trim(), startLine, startCol)
                }
                if (startsWith("<!--")) { skipTo("-->", "comment"); continue }
                if (startsWith("<![CDATA[")) {
                    repeat(9) { advance() }
                    val cs = i
                    skipTo("]]>", "CDATA section")
                    text.append(decode(raw.toString())); raw.setLength(0)
                    text.append(s, cs, i - 3)
                    continue
                }
                if (startsWith("<?")) { skipTo("?>", "processing instruction"); continue }
                if (startsWith("<!")) throw Fail("Declarations inside the file are not supported.", line)
                if (s[i] == '<') { kids += element(depth + 1); continue }
                raw.append(s[i]); advance()
            }
        }

        /** Only the five predefined entities and numeric references. Anything else stays as written: nothing is ever looked up. */
        private fun decode(t: String): String {
            if (t.indexOf('&') < 0) return t
            val out = StringBuilder()
            var k = 0
            while (k < t.length) {
                val c = t[k]
                if (c != '&') { out.append(c); k++; continue }
                val semi = t.indexOf(';', k)
                if (semi < 0 || semi - k > 10) { out.append(c); k++; continue }
                val ent = t.substring(k + 1, semi)
                val rep: String? = when {
                    ent == "lt" -> "<"
                    ent == "gt" -> ">"
                    ent == "amp" -> "&"
                    ent == "quot" -> "\""
                    ent == "apos" -> "'"
                    ent.startsWith("#x") || ent.startsWith("#X") -> ent.drop(2).toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
                    ent.startsWith("#") -> ent.drop(1).toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
                    else -> null
                }
                if (rep == null) { out.append(c); k++ } else { out.append(rep); k = semi + 1 }
            }
            return out.toString()
        }
    }
}
