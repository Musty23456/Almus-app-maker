package com.almus.appcreatorpro.editor

/**
 * Phase 11: an editor colour theme. Colours are ARGB integers stored in a Long (0xFFRRGGBB) so the class has no Android or
 * Compose dependency and is unit-tested on a JVM; the UI converts with Color(value). The existing editor colours
 * (derived from Material light/dark) stay the default until the UI switches to these themes.
 */
data class EditorTheme(
    val id: String,
    val name: String,
    val isDark: Boolean,
    val background: Long,
    val foreground: Long,
    val gutterBackground: Long,
    val gutterForeground: Long,
    val currentLine: Long,
    val selection: Long,
    val caret: Long,
    val bracketMatch: Long,
    val keyword: Long,
    val string: Long,
    val comment: Long,
    val number: Long,
    val annotation: Long,
    val tag: Long,
    val attribute: Long,
) {
    fun colorFor(type: TokenType): Long = when (type) {
        TokenType.KEYWORD -> keyword
        TokenType.STRING -> string
        TokenType.COMMENT -> comment
        TokenType.NUMBER -> number
        TokenType.ANNOTATION -> annotation
        TokenType.TAG -> tag
        TokenType.ATTRIBUTE -> attribute
    }
}

object EditorThemes {
    val ALMUS_LIGHT = EditorTheme(
        id = "almus_light", name = "Almus Light", isDark = false,
        background = 0xFFFFFFFF, foreground = 0xFF1F2328, gutterBackground = 0xFFF3F4F6, gutterForeground = 0xFF6B7280,
        currentLine = 0xFFF1F5F9, selection = 0xFFBBD7FB, caret = 0xFF1F2328, bracketMatch = 0xFFFFE08A,
        keyword = 0xFF0033B3, string = 0xFF1A7F37, comment = 0xFF6A737D, number = 0xFF953800,
        annotation = 0xFF9A6700, tag = 0xFF116329, attribute = 0xFF6F42C1,
    )
    val ALMUS_DARK = EditorTheme(
        id = "almus_dark", name = "Almus Dark", isDark = true,
        background = 0xFF0F1419, foreground = 0xFFE6EDF3, gutterBackground = 0xFF161B22, gutterForeground = 0xFF8B949E,
        currentLine = 0xFF1B222C, selection = 0xFF264F78, caret = 0xFFE6EDF3, bracketMatch = 0xFF5A4A00,
        keyword = 0xFFFF7B72, string = 0xFFA5D6A7, comment = 0xFF8B949E, number = 0xFF79C0FF,
        annotation = 0xFFE3B341, tag = 0xFF7EE787, attribute = 0xFFD2A8FF,
    )
    val SOLARIZED_DARK = EditorTheme(
        id = "solarized_dark", name = "Solarized Dark", isDark = true,
        background = 0xFF002B36, foreground = 0xFF93A1A1, gutterBackground = 0xFF073642, gutterForeground = 0xFF839496,
        currentLine = 0xFF073642, selection = 0xFF0B4A5A, caret = 0xFFEEE8D5, bracketMatch = 0xFF586E75,
        keyword = 0xFF859900, string = 0xFF2AA198, comment = 0xFF7C9099, number = 0xFFD33682,
        annotation = 0xFFCB4B16, tag = 0xFF268BD2, attribute = 0xFFB58900,
    )
    val HIGH_CONTRAST = EditorTheme(
        id = "high_contrast", name = "High Contrast", isDark = true,
        background = 0xFF000000, foreground = 0xFFFFFFFF, gutterBackground = 0xFF000000, gutterForeground = 0xFFFFFFFF,
        currentLine = 0xFF1A1A1A, selection = 0xFF2A4A8A, caret = 0xFFFFFFFF, bracketMatch = 0xFF8A6A00,
        keyword = 0xFF7CC4FF, string = 0xFF8EF08E, comment = 0xFFCFCFCF, number = 0xFFFFB86C,
        annotation = 0xFFFFE066, tag = 0xFF8EF08E, attribute = 0xFFE0B0FF,
    )

    val BUILT_IN: List<EditorTheme> = listOf(ALMUS_LIGHT, ALMUS_DARK, SOLARIZED_DARK, HIGH_CONTRAST)

    /** The theme for an id, or the light/dark default when the id is unknown (for example a deleted custom theme). */
    fun byId(id: String?, systemDark: Boolean, custom: List<EditorTheme> = emptyList()): EditorTheme =
        (BUILT_IN + custom).firstOrNull { it.id == id } ?: if (systemDark) ALMUS_DARK else ALMUS_LIGHT

    /** "#RGB", "#RRGGBB" or "#AARRGGBB" (the # is optional) to 0xAARRGGBB; null when the text is not a colour. */
    fun parseColor(text: String): Long? {
        var s = text.trim().removePrefix("#")
        if (s.length == 3) s = s.map { "$it$it" }.joinToString("")
        if (s.length != 6 && s.length != 8) return null
        if (!s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val v = s.toLong(16)
        return if (s.length == 6) 0xFF000000L or v else v
    }

    fun formatColor(argb: Long): String = "#" + (argb and 0xFFFFFFFFL).toString(16).padStart(8, '0').uppercase()

    private val FIELDS = listOf(
        "background", "foreground", "gutterBackground", "gutterForeground", "currentLine", "selection", "caret",
        "bracketMatch", "keyword", "string", "comment", "number", "annotation", "tag", "attribute",
    )

    val customizableFields: List<String> get() = FIELDS

    /**
     * Builds a custom theme from [base] with the given colour overrides ("keyword" to "#FF0000"). Unknown field names and
     * unreadable colours are reported in the result instead of being ignored silently.
     */
    fun custom(id: String, name: String, base: EditorTheme, overrides: Map<String, String>, isDark: Boolean = base.isDark): ThemeResult {
        if (id.isBlank() || name.isBlank()) return ThemeResult(null, listOf("A custom theme needs an id and a name."))
        if (BUILT_IN.any { it.id == id }) return ThemeResult(null, listOf("The id \"$id\" belongs to a built-in theme."))
        val problems = ArrayList<String>()
        val values = HashMap<String, Long>()
        for ((field, text) in overrides) {
            if (field !in FIELDS) {
                problems.add("Unknown colour: $field")
                continue
            }
            val c = parseColor(text)
            if (c == null) problems.add("Not a colour: $field = $text") else values[field] = c
        }
        if (problems.isNotEmpty()) return ThemeResult(null, problems)
        fun pick(field: String, fallback: Long): Long = values[field] ?: fallback
        val theme = base.copy(
            id = id, name = name, isDark = isDark,
            background = pick("background", base.background), foreground = pick("foreground", base.foreground),
            gutterBackground = pick("gutterBackground", base.gutterBackground), gutterForeground = pick("gutterForeground", base.gutterForeground),
            currentLine = pick("currentLine", base.currentLine), selection = pick("selection", base.selection), caret = pick("caret", base.caret),
            bracketMatch = pick("bracketMatch", base.bracketMatch), keyword = pick("keyword", base.keyword), string = pick("string", base.string),
            comment = pick("comment", base.comment), number = pick("number", base.number), annotation = pick("annotation", base.annotation),
            tag = pick("tag", base.tag), attribute = pick("attribute", base.attribute),
        )
        return ThemeResult(theme, readabilityProblems(theme))
    }

    /** WCAG contrast ratio between two colours, 1.0 (none) to 21.0 (black on white). */
    fun contrastRatio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** Warnings for text colours that are hard to read on the background (contrast below 3:1; comments are allowed less). */
    fun readabilityProblems(t: EditorTheme): List<String> {
        val out = ArrayList<String>()
        val checks = listOf(
            "foreground" to t.foreground, "keyword" to t.keyword, "string" to t.string, "number" to t.number,
            "annotation" to t.annotation, "tag" to t.tag, "attribute" to t.attribute,
        )
        for ((label, color) in checks) {
            if (contrastRatio(color, t.background) < 3.0) out.add("$label is hard to read on the background")
        }
        if (contrastRatio(t.comment, t.background) < 2.0) out.add("comment is hard to read on the background")
        if (contrastRatio(t.gutterForeground, t.gutterBackground) < 2.5) out.add("line numbers are hard to read")
        return out
    }
}

/** [theme] is null when the input was rejected; [problems] then says why, or (with a theme) lists readability warnings. */
data class ThemeResult(val theme: EditorTheme?, val problems: List<String>)
