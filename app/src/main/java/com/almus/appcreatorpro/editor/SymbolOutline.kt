package com.almus.appcreatorpro.editor

enum class SymbolKind { CLASS, INTERFACE, OBJECT, ENUM, FUNCTION, TYPE, SECTION, ELEMENT, KEY, TABLE }

/**
 * One entry of the file outline. [line] and [column] are 0-based and point at the symbol NAME.
 * [indent] is the width of the leading whitespace of the line (the UI can nest by it); [level] is a heading level
 * or key depth where the language has one (Markdown, YAML, HTML headings), otherwise 0.
 */
data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val line: Int,
    val column: Int,
    val indent: Int = 0,
    val level: Int = 0,
)

/**
 * Phase 11: the file outline and the symbol list behind "go to definition". This is a LEXICAL outline: regular expressions
 * over single lines, with matches inside comments and strings ignored. It does not understand types, scopes or
 * declarations that are split over several lines, so it can miss symbols (for example Java methods without a modifier)
 * and cannot tell two overloads or two classes with the same method name apart.
 */
object OutlineEngine {
    const val MAX_SYMBOLS = 2000
    const val MAX_CHARS = 500_000
    private const val MAX_LINE_CHARS = 400

    private val CONTROL_WORDS = setOf(
        "if", "for", "while", "when", "switch", "catch", "return", "else", "do", "synchronized", "try", "finally",
        "new", "await", "throw", "yield", "foreach", "with", "elif",
    )

    private class Rule(
        val regex: Regex,
        val nameGroup: Int,
        val kind: (MatchResult) -> SymbolKind,
        val name: ((MatchResult) -> String)? = null,
        val accept: (MatchResult) -> Boolean = { true },
    )

    private fun kindFor(keyword: String): SymbolKind = when (keyword) {
        "class", "record", "struct", "extension" -> SymbolKind.CLASS
        "interface", "mixin", "protocol" -> SymbolKind.INTERFACE
        "object" -> SymbolKind.OBJECT
        "enum" -> SymbolKind.ENUM
        "typealias", "type", "typedef" -> SymbolKind.TYPE
        else -> SymbolKind.FUNCTION
    }

    private fun keywordRule(regex: String, keywordGroup: Int, nameGroup: Int) = Rule(
        Regex(regex), nameGroup, { kindFor(it.groupValues[keywordGroup]) },
        accept = { it.groupValues[nameGroup] !in CONTROL_WORDS },
    )

    private fun functionRule(regex: String, nameGroup: Int) = Rule(
        Regex(regex), nameGroup, { SymbolKind.FUNCTION },
        accept = { it.groupValues[nameGroup] !in CONTROL_WORDS },
    )

    private val KOTLIN = listOf(
        keywordRule(
            "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|internal|open|abstract|sealed|final|data|enum|annotation|inline|suspend|override|operator|infix|tailrec|external|expect|actual|value|fun)\\s+)*(class|interface|object|fun|typealias)\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?, ]+\\.)?(\\w+)",
            1, 2,
        ),
    )

    private val JAVA = listOf(
        keywordRule(
            "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|static|final|abstract|sealed|non-sealed|strictfp)\\s+)*(class|interface|enum|record)\\s+(\\w+)",
            1, 2,
        ),
        functionRule(
            "^\\s*(?:@[\\w.]+\\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default)\\s+)+(?:<[^>]*>\\s+)?[\\w.\\[\\]?]+(?:\\s*<[^>]*>)?(?:\\[\\])*\\s+(\\w+)\\s*\\(",
            1,
        ),
        functionRule("^\\s*(?:public|private|protected)\\s+(\\w+)\\s*\\(", 1),
    )

    private val JAVASCRIPT = listOf(
        keywordRule("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?(function)\\s*\\*?\\s*(\\w+)", 1, 2),
        keywordRule("^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?(class)\\s+(\\w+)", 1, 2),
        functionRule(
            "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*(?::\\s*[\\w<>\\[\\]| ]+)?\\s*=>",
            1,
        ),
        functionRule("^\\s+(?:(?:public|private|protected|static|async|readonly|get|set)\\s+)*(\\w+)\\s*\\([^)]*\\)\\s*(?::\\s*[^{]+)?\\{\\s*$", 1),
    )

    private val TYPESCRIPT = listOf(
        keywordRule("^\\s*(?:export\\s+)?(?:declare\\s+)?(interface|type|enum)\\s+(\\w+)", 1, 2),
    ) + JAVASCRIPT

    private val PYTHON = listOf(
        keywordRule("^\\s*(?:async\\s+)?(def|class)\\s+(\\w+)", 1, 2),
    )

    private val DART = listOf(
        keywordRule("^\\s*(?:abstract\\s+|base\\s+|final\\s+|sealed\\s+|interface\\s+)*(class|mixin|enum|extension|typedef)\\s+(\\w+)", 1, 2),
        Rule(
            Regex("^\\s*(?:@\\w+\\s+)*(?:static\\s+)?([\\w<>?,.\\[\\]]+)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:async\\s*\\*?\\s*)?(?:\\{|=>)"),
            2, { SymbolKind.FUNCTION },
            accept = { it.groupValues[2] !in CONTROL_WORDS && it.groupValues[1] !in CONTROL_WORDS },
        ),
    )

    private val CSS = listOf(
        Rule(Regex("^\\s*([^\\s{};/*][^{};]*?)\\s*\\{\\s*$"), 1, { SymbolKind.SECTION }),
    )

    private val XML = listOf(
        Rule(
            Regex("<(\\w[\\w.:-]*)\\b[^>]*?\\b(?:android:id|id)\\s*=\\s*\"(?:@\\+?id/)?([^\"]+)\""),
            2, { SymbolKind.ELEMENT }, name = { it.groupValues[1] + "#" + it.groupValues[2] },
        ),
    )

    private val HTML = listOf(
        Rule(
            Regex("<h([1-6])\\b[^>]*>(.*?)</h\\1>"), 2, { SymbolKind.SECTION },
            name = { it.groupValues[2].replace(Regex("<[^>]*>"), "").trim() },
            accept = { it.groupValues[2].replace(Regex("<[^>]*>"), "").isNotBlank() },
        ),
    ) + XML

    private val SQL = listOf(
        Rule(
            Regex(
                "^\\s*create\\s+(?:or\\s+replace\\s+)?(?:temp(?:orary)?\\s+)?(?:unique\\s+)?(table|view|index|function|procedure|trigger)\\s+(?:if\\s+not\\s+exists\\s+)?([\\w.\"`\\[\\]]+)",
                RegexOption.IGNORE_CASE,
            ),
            2, { if (it.groupValues[1].lowercase() == "table") SymbolKind.TABLE else SymbolKind.TYPE },
        ),
    )

    fun outline(text: String, language: Language): List<Symbol> {
        if (text.isEmpty() || text.length > MAX_CHARS) return emptyList()
        val index = LineIndex(text)
        val all = when (language) {
            Language.JSON -> jsonKeys(text, index)
            Language.MARKDOWN -> markdown(index)
            Language.YAML -> yaml(index)
            else -> ruleBased(text, language, index)
        }
        return if (all.size > MAX_SYMBOLS) all.subList(0, MAX_SYMBOLS) else all
    }

    private fun rulesFor(language: Language): List<Rule> = when (language) {
        Language.KOTLIN -> KOTLIN
        Language.JAVA -> JAVA
        Language.JAVASCRIPT -> JAVASCRIPT
        Language.TYPESCRIPT -> TYPESCRIPT
        Language.PYTHON -> PYTHON
        Language.DART -> DART
        Language.CSS -> CSS
        Language.XML -> XML
        Language.HTML -> HTML
        Language.SQL -> SQL
        else -> emptyList()
    }

    private fun ruleBased(text: String, language: Language, index: LineIndex): List<Symbol> {
        val rules = rulesFor(language)
        if (rules.isEmpty()) return emptyList()
        val tokens = SyntaxHighlighter.tokenize(text, language).filter { it.type == TokenType.COMMENT || it.type == TokenType.STRING }
        val out = ArrayList<Symbol>()
        for (line in 0 until index.lineCount) {
            val raw = index.lineText(line).trimEnd('\r')
            if (raw.isBlank() || raw.length > MAX_LINE_CHARS) continue
            val indent = raw.length - raw.trimStart().length
            // Markup elements may start anywhere in the line; every other rule starts with the declaration.
            val probe = index.lineStart(line) + indent
            if (language.family != SyntaxFamily.MARKUP && insideNonCode(tokens, probe)) continue
            for (rule in rules) {
                val m = rule.regex.find(raw) ?: continue
                if (!rule.accept(m)) continue
                val range = m.groups[rule.nameGroup]?.range ?: continue
                val name = rule.name?.invoke(m) ?: m.groupValues[rule.nameGroup]
                if (name.isEmpty()) continue
                if (language.family == SyntaxFamily.MARKUP && insideNonCode(tokens, index.lineStart(line) + m.range.first)) continue
                out += Symbol(name, rule.kind(m), line, range.first, indent)
                break
            }
            if (out.size >= MAX_SYMBOLS) break
        }
        return out
    }

    private fun insideNonCode(tokens: List<Token>, offset: Int): Boolean {
        var lo = 0
        var hi = tokens.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val t = tokens[mid]
            when {
                offset < t.start -> hi = mid - 1
                offset >= t.end -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    private fun markdown(index: LineIndex): List<Symbol> {
        val out = ArrayList<Symbol>()
        var fenced = false
        for (line in 0 until index.lineCount) {
            val raw = index.lineText(line).trimEnd('\r')
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                fenced = !fenced
                continue
            }
            if (fenced || !raw.startsWith("#")) continue
            var level = 0
            while (level < raw.length && raw[level] == '#') level++
            if (level > 6 || level >= raw.length || raw[level] != ' ') continue
            val title = raw.substring(level + 1).trim().trimEnd('#').trim()
            if (title.isEmpty()) continue
            out += Symbol(title, SymbolKind.SECTION, line, level + 1, 0, level)
            if (out.size >= MAX_SYMBOLS) break
        }
        return out
    }

    private val YAML_KEY = Regex("^(\\s*)(?:-\\s+)?([A-Za-z_][\\w.\\-/]*)\\s*:(?:\\s|$)")

    private fun yaml(index: LineIndex): List<Symbol> {
        val out = ArrayList<Symbol>()
        for (line in 0 until index.lineCount) {
            val raw = index.lineText(line).trimEnd('\r')
            if (raw.length > MAX_LINE_CHARS) continue
            val m = YAML_KEY.find(raw) ?: continue
            val indent = m.groupValues[1].length
            if (indent > 2) continue
            val nameRange = m.groups[2]?.range ?: continue
            out += Symbol(m.groupValues[2], SymbolKind.KEY, line, nameRange.first, indent, indent / 2)
            if (out.size >= MAX_SYMBOLS) break
        }
        return out
    }

    private fun jsonKeys(text: String, index: LineIndex): List<Symbol> {
        val out = ArrayList<Symbol>()
        var depth = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '"') {
                var j = i + 1
                while (j < n && text[j] != '"' && text[j] != '\n') j += if (text[j] == '\\') 2 else 1
                val end = minOf(n, j + 1)
                if (depth == 1) {
                    var k = end
                    while (k < n && text[k].isWhitespace()) k++
                    if (k < n && text[k] == ':') {
                        val name = text.substring(i + 1, (end - 1).coerceAtLeast(i + 1))
                        out += Symbol(name, SymbolKind.KEY, index.lineOf(i), index.columnOf(i + 1), 0, 0)
                        if (out.size >= MAX_SYMBOLS) break
                    }
                }
                i = end
            } else {
                if (c == '{' || c == '[') depth++
                if (c == '}' || c == ']') depth--
                i++
            }
        }
        return out
    }
}
