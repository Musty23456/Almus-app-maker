package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.editor.Language

/** What a file is, for icons, labels, filters and the structure view. */
enum class FileKind(val label: String, val badge: String, val language: Language, val isBinary: Boolean = false) {
    KOTLIN("Kotlin", "KT", Language.KOTLIN),
    JAVA("Java", "JV", Language.JAVA),
    XML("XML", "XML", Language.XML),
    JSON("JSON", "{ }", Language.JSON),
    YAML("YAML", "YML", Language.YAML),
    GRADLE("Gradle", "GR", Language.KOTLIN),
    PYTHON("Python", "PY", Language.PYTHON),
    JAVASCRIPT("JavaScript", "JS", Language.JAVASCRIPT),
    TYPESCRIPT("TypeScript", "TS", Language.TYPESCRIPT),
    HTML("HTML", "HTM", Language.HTML),
    CSS("CSS", "CSS", Language.CSS),
    MARKDOWN("Markdown", "MD", Language.MARKDOWN),
    SQL("SQL", "SQL", Language.SQL),
    DART("Dart", "DRT", Language.DART),
    PROPERTIES("Properties / TOML", "CFG", Language.YAML),
    TEXT("Text", "TXT", Language.PLAIN),
    IMAGE("Image", "IMG", Language.PLAIN, isBinary = true),
    BINARY("Binary", "BIN", Language.PLAIN, isBinary = true),
    OTHER("Other", "FILE", Language.PLAIN),
}

/** Recognizes a file's kind from its path (special names first, then the extension). */
object FileTypes {

    private val textNames = setOf(
        "readme", "license", "licence", "notice", "changelog", "authors", "contributing",
        ".gitignore", ".gitattributes", ".editorconfig", "dockerfile", "makefile", "gradlew", "gradlew.bat",
    )
    private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg")
    private val binaryExt = setOf(
        "jar", "aar", "apk", "aab", "zip", "gz", "so", "dex", "class", "keystore", "jks", "ttf", "otf", "woff", "woff2",
        "mp3", "mp4", "wav", "ogg", "pdf", "bin", "db", "sqlite",
    )

    fun recognize(path: String): FileKind {
        val name = path.substringAfterLast('/')
        val lower = name.lowercase()
        if (lower.endsWith(".gradle") || lower.endsWith(".gradle.kts")) return FileKind.GRADLE
        if (lower == "libs.versions.toml") return FileKind.PROPERTIES
        if (lower in textNames) return FileKind.TEXT
        return when (lower.substringAfterLast('.', "")) {
            "kt", "kts" -> FileKind.KOTLIN
            "java" -> FileKind.JAVA
            "xml" -> FileKind.XML
            "json" -> FileKind.JSON
            "yaml", "yml" -> FileKind.YAML
            "py", "pyw" -> FileKind.PYTHON
            "js", "mjs", "cjs", "jsx" -> FileKind.JAVASCRIPT
            "ts", "tsx", "mts" -> FileKind.TYPESCRIPT
            "html", "htm" -> FileKind.HTML
            "css" -> FileKind.CSS
            "md", "markdown" -> FileKind.MARKDOWN
            "sql" -> FileKind.SQL
            "dart" -> FileKind.DART
            "properties", "toml", "ini", "cfg", "pro" -> FileKind.PROPERTIES
            "txt", "log", "csv" -> FileKind.TEXT
            in imageExt -> FileKind.IMAGE
            in binaryExt -> FileKind.BINARY
            else -> FileKind.OTHER
        }
    }

    /** True for kinds that are surely not UTF-8 text; such files get an info card instead of a text preview. */
    fun isBinary(path: String): Boolean = recognize(path).isBinary && !path.lowercase().endsWith(".svg")
}
