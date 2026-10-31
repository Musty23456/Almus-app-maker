package com.almus.appcreatorpro.editor

enum class SyntaxFamily { C_LIKE, MARKUP, NONE }

/** Languages the editor can highlight. Anything else is shown as plain text. */
enum class Language(
    val displayName: String,
    val family: SyntaxFamily,
    val keywords: Set<String> = emptySet(),
    val lineComment: String? = null,
    val blockComments: Boolean = false,
    val tripleQuotes: Boolean = false,
    val backtickStrings: Boolean = false,
    val annotations: Boolean = false,
    val hexColors: Boolean = false,
) {
    KOTLIN(
        "Kotlin", SyntaxFamily.C_LIKE,
        keywords = setOf(
            "package", "import", "class", "interface", "object", "fun", "val", "var", "if", "else", "when",
            "for", "while", "do", "return", "break", "continue", "in", "is", "as", "try", "catch", "finally",
            "throw", "null", "true", "false", "this", "super", "override", "open", "abstract", "sealed", "data",
            "enum", "companion", "private", "public", "protected", "internal", "suspend", "inline", "const",
            "lateinit", "by", "init", "constructor", "typealias", "where", "out", "vararg",
        ),
        lineComment = "//", blockComments = true, tripleQuotes = true, annotations = true,
    ),
    JAVA(
        "Java", SyntaxFamily.C_LIKE,
        keywords = setOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "true", "false", "null", "var", "record",
        ),
        lineComment = "//", blockComments = true, annotations = true,
    ),
    JAVASCRIPT(
        "JavaScript", SyntaxFamily.C_LIKE,
        keywords = setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "break",
            "continue", "switch", "case", "default", "new", "delete", "typeof", "instanceof", "in", "of",
            "class", "extends", "super", "this", "import", "export", "from", "async", "await", "try",
            "catch", "finally", "throw", "null", "undefined", "true", "false", "yield",
        ),
        lineComment = "//", blockComments = true, backtickStrings = true,
    ),
    CSS("CSS", SyntaxFamily.C_LIKE, blockComments = true, annotations = true, hexColors = true),
    JSON("JSON", SyntaxFamily.C_LIKE, keywords = setOf("true", "false", "null")),
    XML("XML", SyntaxFamily.MARKUP),
    HTML("HTML", SyntaxFamily.MARKUP),
    MARKDOWN("Markdown", SyntaxFamily.NONE),
    PLAIN("Plain text", SyntaxFamily.NONE),
    ;

    companion object {
        fun fromFileName(fileName: String): Language {
            val name = fileName.substringAfterLast('/')
            return when (name.substringAfterLast('.', "").lowercase()) {
                "kt", "kts", "gradle" -> KOTLIN
                "java" -> JAVA
                "js", "mjs" -> JAVASCRIPT
                "css" -> CSS
                "json" -> JSON
                "xml" -> XML
                "html", "htm" -> HTML
                "md", "markdown" -> MARKDOWN
                else -> PLAIN
            }
        }
    }
}
