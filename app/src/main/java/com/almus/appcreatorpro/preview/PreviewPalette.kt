package com.almus.appcreatorpro.preview

/**
 * Stand-in colors for the approximate previews. These resemble Material 3 baseline colors; they are NOT read from the
 * app's real theme (themes and styles are not evaluated).
 */
data class PreviewPalette(
    val background: Int,
    val surface: Int,
    val onSurface: Int,
    val primary: Int,
    val onPrimary: Int,
    val outline: Int,
    val placeholder: Int,
) {
    companion object {
        val LIGHT = PreviewPalette(0xFFFFFBFE.toInt(), 0xFFF3EDF7.toInt(), 0xFF1C1B1F.toInt(), 0xFF6750A4.toInt(), 0xFFFFFFFF.toInt(), 0xFF79747E.toInt(), 0xFFE7E0EC.toInt())
        val DARK = PreviewPalette(0xFF1C1B1F.toInt(), 0xFF2B2930.toInt(), 0xFFE6E1E5.toInt(), 0xFFD0BCFF.toInt(), 0xFF381E72.toInt(), 0xFF938F99.toInt(), 0xFF49454F.toInt())
        fun of(dark: Boolean) = if (dark) DARK else LIGHT
    }
}
