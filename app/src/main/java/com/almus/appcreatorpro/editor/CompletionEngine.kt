package com.almus.appcreatorpro.editor

enum class CompletionKind { KEYWORD, SNIPPET, SYMBOL, WORD }

/** [insertText] may contain the caret marker [CompletionEngine.CARET] and '\n' (indentation is added when it is applied). */
data class Completion(
    val label: String,
    val insertText: String,
    val kind: CompletionKind,
    val detail: String? = null,
    val score: Int = 0,
)

/**
 * Phase 11: suggestions while typing. LEXICAL, not semantic: candidates are the language keywords, a few snippets, the
 * identifiers already present in the file, and any extra symbol names the caller supplies (for example from other project
 * files). There is no type information, so nothing is offered after a '.' that depends on the receiver's type.
 * No suggestions are made inside comments and strings.
 */
object CompletionEngine {
    const val CARET = "\$0"
    const val MAX_SCAN_CHARS = 300_000
    private const val MIN_WORD_LENGTH = 3

    private class Snippet(val trigger: String, val detail: String, val body: String)

    private val KOTLIN_SNIPPETS = listOf(
        Snippet("main", "fun main()", "fun main() {\n    \$0\n}"),
        Snippet("fori", "for (i in 0 until n)", "for (i in 0 until \$0) {\n    \n}"),
        Snippet("foreach", "for (item in items)", "for (item in \$0) {\n    \n}"),
        Snippet("ifelse", "if … else …", "if (\$0) {\n    \n} else {\n    \n}"),
        Snippet("trycatch", "try … catch", "try {\n    \$0\n} catch (e: Exception) {\n    \n}"),
        Snippet("dataclass", "data class", "data class \$0(val name: String)"),
    )
    private val JAVA_SNIPPETS = listOf(
        Snippet("main", "public static void main", "public static void main(String[] args) {\n    \$0\n}"),
        Snippet("fori", "for (int i …)", "for (int i = 0; i < \$0; i++) {\n    \n}"),
        Snippet("ifelse", "if … else …", "if (\$0) {\n    \n} else {\n    \n}"),
        Snippet("trycatch", "try … catch", "try {\n    \$0\n} catch (Exception e) {\n    \n}"),
        Snippet("sout", "System.out.println", "System.out.println(\$0);"),
    )
    private val JS_SNIPPETS = listOf(
        Snippet("fori", "for (let i …)", "for (let i = 0; i < \$0; i++) {\n    \n}"),
        Snippet("arrow", "const f = () => {}", "const \$0 = () => {\n    \n};"),
        Snippet("trycatch", "try … catch", "try {\n    \$0\n} catch (error) {\n    \n}"),
        Snippet("clog", "console.log", "console.log(\$0);"),
    )
    private val PYTHON_SNIPPETS = listOf(
        Snippet("main", "if __name__ == \"__main__\"", "if __name__ == \"__main__\":\n    \$0"),
        Snippet("deff", "def name():", "def \$0():\n    pass"),
        Snippet("classs", "class Name:", "class \$0:\n    def __init__(self):\n        pass"),
        Snippet("trycatch", "try … except", "try:\n    \$0\nexcept Exception as e:\n    pass"),
    )
    private val DART_SNIPPETS = listOf(
        Snippet("main", "void main()", "void main() {\n  \$0\n}"),
        Snippet("fori", "for (var i …)", "for (var i = 0; i < \$0; i++) {\n  \n}"),
        Snippet("stless", "StatelessWidget", "class \$0 extends StatelessWidget {\n  const \$0({super.key});\n\n  @override\n  Widget build(BuildContext context) {\n    return Container();\n  }\n}"),
    )
    private val HTML_SNIPPETS = listOf(
        Snippet("html5", "HTML5 page", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n    <meta charset=\"utf-8\">\n    <title>\$0</title>\n</head>\n<body>\n    \n</body>\n</html>"),
        Snippet("link", "stylesheet link", "<link rel=\"stylesheet\" href=\"\$0\">"),
        Snippet("script", "script tag", "<script src=\"\$0\"></script>"),
    )
    private val SQL_SNIPPETS = listOf(
        Snippet("seltab", "SELECT … FROM", "SELECT * FROM \$0 WHERE 1 = 1;"),
        Snippet("crtab", "CREATE TABLE", "CREATE TABLE \$0 (\n    id INTEGER PRIMARY KEY\n);"),
    )

    private fun snippetsFor(language: Language): List<Snippet> = when (language) {
        Language.KOTLIN -> KOTLIN_SNIPPETS
        Language.JAVA -> JAVA_SNIPPETS
        Language.JAVASCRIPT, Language.TYPESCRIPT -> JS_SNIPPETS
        Language.PYTHON -> PYTHON_SNIPPETS
        Language.DART -> DART_SNIPPETS
        Language.HTML -> HTML_SNIPPETS
        Language.SQL -> SQL_SNIPPETS
        else -> emptyList()
    }

    private fun isWordChar(c: Char, language: Language): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '$' ||
            (c == '-' && (language == Language.CSS || language.family == SyntaxFamily.MARKUP || language == Language.YAML))

    /** The identifier characters directly before [cursor]. */
    fun prefixAt(text: String, cursor: Int, language: Language = Language.PLAIN): String {
        val end = cursor.coerceIn(0, text.length)
        var start = end
        while (start > 0 && isWordChar(text[start - 1], language)) start--
        return text.substring(start, end)
    }

    private fun insideCommentOrString(text: String, cursor: Int, language: Language): Boolean {
        if (language.family != SyntaxFamily.C_LIKE && language.family != SyntaxFamily.MARKUP) return false
        for (t in SyntaxHighlighter.tokenize(text, language)) {
            if (t.start >= cursor) break
            if (t.type == TokenType.COMMENT && cursor > t.start && cursor <= t.end) return true
            if (t.type == TokenType.STRING && cursor > t.start && cursor < t.end) return true
        }
        return false
    }

    /** 100 = same-case prefix, 80 = prefix ignoring case, 50 = camel-hump initials ("gtl" -> getTotalLength), 20 = in-order letters, 0 = no match. */
    fun matchScore(label: String, prefix: String): Int {
        if (prefix.isEmpty()) return 10
        if (label.startsWith(prefix)) return 100
        if (label.startsWith(prefix, ignoreCase = true)) return 80
        if (prefix.length < 2) return 0
        val initials = StringBuilder()
        for ((i, ch) in label.withIndex()) {
            if (i == 0 || ch.isUpperCase() || (i > 0 && label[i - 1] == '_' && ch != '_')) initials.append(ch.lowercaseChar())
        }
        if (initials.startsWith(prefix.lowercase())) return 50
        var p = 0
        val pl = prefix.lowercase()
        for (ch in label.lowercase()) {
            if (p < pl.length && ch == pl[p]) p++
        }
        return if (p == pl.length) 20 else 0
    }

    fun suggest(
        text: String,
        cursor: Int,
        language: Language,
        extraSymbols: Collection<String> = emptyList(),
        limit: Int = 12,
        force: Boolean = false,
    ): List<Completion> {
        val prefix = prefixAt(text, cursor, language)
        if (prefix.isEmpty() && !force) return emptyList()
        if (language == Language.PLAIN || language == Language.MARKDOWN) return emptyList()
        if (insideCommentOrString(text, cursor, language)) return emptyList()

        val found = LinkedHashMap<String, Completion>()
        fun offer(label: String, insert: String, kind: CompletionKind, detail: String?, bonus: Int) {
            if (label == prefix && kind != CompletionKind.SNIPPET) return
            val base = matchScore(label, prefix)
            if (base == 0) return
            val key = kind.name + ":" + label
            val c = Completion(label, insert, kind, detail, base + bonus)
            val old = found[key]
            if (old == null || old.score < c.score) found[key] = c
        }
        for (s in snippetsFor(language)) offer(s.trigger, s.body, CompletionKind.SNIPPET, s.detail, 6)
        for (k in language.keywords) offer(k, k, CompletionKind.KEYWORD, null, 5)
        for (sym in extraSymbols) offer(sym, sym, CompletionKind.SYMBOL, "project", 10)

        val scan = if (text.length > MAX_SCAN_CHARS) text.substring(0, MAX_SCAN_CHARS) else text
        val seen = HashSet<String>()
        var i = 0
        while (i < scan.length) {
            if (scan[i].isLetter() || scan[i] == '_' || scan[i] == '$') {
                var j = i + 1
                while (j < scan.length && isWordChar(scan[j], language)) j++
                val word = scan.substring(i, j)
                // The word being typed is part of the text; do not offer it back to itself.
                val isTyping = j == cursor && word == prefix
                if (!isTyping && word.length >= MIN_WORD_LENGTH && seen.add(word) && word !in language.keywords) {
                    offer(word, word, CompletionKind.WORD, null, 0)
                }
                i = j
            } else {
                i++
            }
        }
        return found.values
            .sortedWith(compareByDescending<Completion> { it.score }.thenBy { it.label.length }.thenBy { it.label })
            .take(limit.coerceAtLeast(1))
    }

    /** Replaces the typed prefix with the completion. Snippet lines after the first get the current line's indentation. */
    fun apply(value: EditorValue, completion: Completion, language: Language = Language.PLAIN): EditorValue {
        val v = value.coerced()
        val caret = v.cursor
        val prefix = prefixAt(v.text, caret, language)
        val start = caret - prefix.length
        val lineStart = v.text.lastIndexOf('\n', (start - 1).coerceAtLeast(0)).let { if (start == 0) 0 else it + 1 }
        var indent = ""
        run {
            var k = lineStart
            while (k < v.text.length && (v.text[k] == ' ' || v.text[k] == '\t')) k++
            indent = v.text.substring(lineStart, minOf(k, start))
        }
        val body = completion.insertText.replace("\n", "\n" + indent)
        val marker = body.indexOf(CARET)
        val clean = body.replace(CARET, "")
        val newText = v.text.substring(0, start) + clean + v.text.substring(caret)
        val newCaret = start + (if (marker >= 0) marker else clean.length)
        return EditorValue(newText, newCaret, newCaret)
    }
}
