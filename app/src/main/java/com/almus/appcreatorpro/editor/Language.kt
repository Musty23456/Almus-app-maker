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
    /** Phase 11: SQL keywords match in any case; [keywords] must then be lower case. */
    val caseInsensitiveKeywords: Boolean = false,
    /** Phase 11: Python and Dart also use three single quotes for multi-line strings. */
    val tripleSingleQuotes: Boolean = false,
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
    PYTHON(
        "Python", SyntaxFamily.C_LIKE,
        keywords = setOf(
            "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del",
            "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal",
            "not", "or", "pass", "raise", "return", "try", "while", "with", "yield", "match", "case", "self",
        ),
        lineComment = "#", tripleQuotes = true, tripleSingleQuotes = true, annotations = true,
    ),
    TYPESCRIPT(
        "TypeScript", SyntaxFamily.C_LIKE,
        keywords = setOf(
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "do", "break", "continue", "switch",
            "case", "default", "new", "delete", "typeof", "instanceof", "in", "of", "class", "extends", "implements", "super",
            "this", "import", "export", "from", "async", "await", "try", "catch", "finally", "throw", "null", "undefined",
            "true", "false", "yield", "interface", "type", "enum", "namespace", "declare", "readonly", "public", "private",
            "protected", "abstract", "as", "keyof", "is", "never", "unknown", "any", "string", "number", "boolean", "void",
        ),
        lineComment = "//", blockComments = true, backtickStrings = true, annotations = true,
    ),
    DART(
        "Dart", SyntaxFamily.C_LIKE,
        keywords = setOf(
            "abstract", "as", "assert", "async", "await", "break", "case", "catch", "class", "const", "continue", "default",
            "do", "else", "enum", "extends", "extension", "factory", "false", "final", "finally", "for", "get", "if",
            "implements", "import", "in", "is", "late", "library", "mixin", "new", "null", "on", "operator", "part", "required",
            "rethrow", "return", "set", "static", "super", "switch", "this", "throw", "true", "try", "typedef", "var", "void",
            "while", "with", "yield", "int", "double", "String", "bool", "dynamic",
        ),
        lineComment = "//", blockComments = true, tripleQuotes = true, tripleSingleQuotes = true, annotations = true,
    ),
    YAML(
        "YAML", SyntaxFamily.C_LIKE,
        keywords = setOf("true", "false", "null", "yes", "no", "on", "off"),
        lineComment = "#",
    ),
    SQL(
        "SQL", SyntaxFamily.C_LIKE,
        keywords = setOf(
            "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "alter", "drop",
            "table", "view", "index", "trigger", "function", "procedure", "database", "schema", "primary", "foreign", "key",
            "references", "constraint", "unique", "not", "null", "default", "check", "and", "or", "in", "is", "like", "between",
            "exists", "join", "inner", "left", "right", "full", "outer", "cross", "on", "using", "group", "by", "order",
            "having", "limit", "offset", "union", "all", "distinct", "as", "case", "when", "then", "else", "end", "asc", "desc",
            "begin", "commit", "rollback", "transaction", "if", "with", "returning", "true", "false", "integer", "int", "text",
            "varchar", "boolean", "real", "blob", "date", "timestamp", "autoincrement", "pragma", "replace",
        ),
        lineComment = "--", blockComments = true, caseInsensitiveKeywords = true,
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
                "js", "mjs", "cjs", "jsx" -> JAVASCRIPT
                "py", "pyw" -> PYTHON
                "ts", "tsx", "mts" -> TYPESCRIPT
                "dart" -> DART
                "yaml", "yml" -> YAML
                "sql" -> SQL
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
