package com.almus.appcreatorpro.domain.validation

object PackageNameSuggester {
    private const val PREFIX = "com.example."

    /**
     * "Weather Buddy" -> "com.example.weatherbuddy".
     * Returns an empty string when the name has no usable letters or digits.
     */
    fun suggest(projectName: String): String {
        val slug = projectName.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
        if (slug.isEmpty()) return ""
        val safe = if (slug.first().isDigit()) "app$slug" else slug
        return PREFIX + safe
    }
}
