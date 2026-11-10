package com.almus.appcreatorpro.preview

enum class TokKind { ID, STR, NUM, SYM }

data class Tok(val kind: TokKind, val text: String, val line: Int) {
    fun isSym(s: String) = kind == TokKind.SYM && text == s
    fun isId(s: String) = kind == TokKind.ID && text == s
}

/**
 * A minimal Kotlin tokenizer, enough to read the SHAPE of @Composable code: identifiers, string and number literals,
 * single-character symbols. Comments are dropped. String templates are kept inside the string token as written.
 * It never evaluates anything.
 */
object KtLexer {
    const val MAX_CHARS = 400_000

    fun tokenize(src: String): List<Tok> {
        val s = if (src.length > MAX_CHARS) src.substring(0, MAX_CHARS) else src
        val out = ArrayList<Tok>()
        var i = 0
        var line = 1
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '\n' -> { line++; i++ }
                c.isWhitespace() -> i++
                c == '/' && i + 1 < n && s[i + 1] == '/' -> { while (i < n && s[i] != '\n') i++ }
                c == '/' && i + 1 < n && s[i + 1] == '*' -> {
                    i += 2
                    while (i < n && !(s[i] == '*' && i + 1 < n && s[i + 1] == '/')) { if (s[i] == '\n') line++; i++ }
                    i = minOf(n, i + 2)
                }
                s.startsWith("\"\"\"", i) -> {
                    val start = line
                    i += 3
                    val st = i
                    while (i < n && !s.startsWith("\"\"\"", i)) { if (s[i] == '\n') line++; i++ }
                    out += Tok(TokKind.STR, s.substring(st, minOf(i, n)), start)
                    i = minOf(n, i + 3)
                }
                c == '"' -> {
                    i++
                    val st = i
                    var depth = 0
                    while (i < n) {
                        val ch = s[i]
                        if (ch == '\\') { i += 2; continue }
                        if (depth == 0 && ch == '"') break
                        if (ch == '$' && i + 1 < n && s[i + 1] == '{') { depth++; i += 2; continue }
                        if (depth > 0 && ch == '{') depth++
                        if (depth > 0 && ch == '}') depth--
                        if (ch == '\n') { if (depth == 0) break; line++ }
                        i++
                    }
                    out += Tok(TokKind.STR, s.substring(st, minOf(i, n)), line)
                    if (i < n && s[i] == '"') i++
                }
                c == '\'' -> {
                    i++
                    val st = i
                    while (i < n && s[i] != '\'' && s[i] != '\n') { if (s[i] == '\\') i++; i++ }
                    out += Tok(TokKind.STR, s.substring(st, minOf(i, n)), line)
                    i = minOf(n, i + 1)
                }
                c == '`' -> {
                    i++
                    val st = i
                    while (i < n && s[i] != '`' && s[i] != '\n') i++
                    out += Tok(TokKind.ID, s.substring(st, minOf(i, n)), line)
                    i = minOf(n, i + 1)
                }
                c.isLetter() || c == '_' -> {
                    val st = i
                    while (i < n && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    out += Tok(TokKind.ID, s.substring(st, i), line)
                }
                c.isDigit() -> {
                    val st = i
                    while (i < n) {
                        val ch = s[i]
                        if (ch.isLetterOrDigit() || ch == '_') i++
                        else if (ch == '.' && i + 1 < n && s[i + 1].isDigit()) i++
                        else break
                    }
                    out += Tok(TokKind.NUM, s.substring(st, i), line)
                }
                else -> { out += Tok(TokKind.SYM, c.toString(), line); i++ }
            }
        }
        return out
    }

    /** Index of the bracket that closes the one at [open], or -1. Only the same kind of bracket is counted. */
    fun matching(toks: List<Tok>, open: Int): Int {
        val o = toks[open].text
        val close = when (o) {
            "(" -> ")"
            "{" -> "}"
            "[" -> "]"
            else -> return -1
        }
        var depth = 0
        for (k in open until toks.size) {
            val t = toks[k]
            if (t.kind != TokKind.SYM) continue
            if (t.text == o) depth++
            else if (t.text == close) { depth--; if (depth == 0) return k }
        }
        return -1
    }
}

data class ComposableFn(val name: String, val isPreview: Boolean, val line: Int, val tokenIndex: Int)

/** Finds the `@Composable fun` declarations of one source file. */
object ComposeSource {
    fun functions(toks: List<Tok>): List<ComposableFn> {
        val out = ArrayList<ComposableFn>()
        var pending = HashSet<String>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            if (t.isSym("@") && i + 1 < toks.size && toks[i + 1].kind == TokKind.ID) {
                pending.add(toks[i + 1].text)
                i += 2
                // Skip a use-site target or qualified name (@file:Foo, @androidx.x.Preview) and an argument list.
                while (i + 1 < toks.size && (toks[i].isSym(".") || toks[i].isSym(":")) && toks[i + 1].kind == TokKind.ID) { pending.add(toks[i + 1].text); i += 2 }
                if (i < toks.size && toks[i].isSym("(")) { val m = KtLexer.matching(toks, i); i = if (m < 0) toks.size else m + 1 }
                continue
            }
            if (t.kind == TokKind.ID && t.text == "fun") {
                var j = i + 1
                if (j < toks.size && toks[j].isSym("<")) { while (j < toks.size && !toks[j].isSym(">")) j++; j++ }
                val nameTok = toks.getOrNull(j)
                if (nameTok != null && nameTok.kind == TokKind.ID && toks.getOrNull(j + 1)?.isSym("(") == true && "Composable" in pending) {
                    out += ComposableFn(nameTok.text, "Preview" in pending, t.line, i)
                }
                pending = HashSet()
            } else if (t.kind == TokKind.ID && (t.text == "class" || t.text == "object" || t.text == "val" || t.text == "var" || t.text == "interface")) {
                pending = HashSet()
            }
            i++
        }
        return out
    }
}
