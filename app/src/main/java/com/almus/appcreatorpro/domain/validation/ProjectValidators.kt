package com.almus.appcreatorpro.domain.validation

/** Result of validating one input field. */
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val message: String) : ValidationResult

    val isValid: Boolean get() = this is Valid
    val errorMessage: String? get() = (this as? Invalid)?.message
}

object ProjectNameValidator {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 40

    private val allowed = Regex("^[A-Za-z0-9][A-Za-z0-9 _.\\-]*$")

    /** @param existingNames names already in use; comparison ignores case and outer whitespace. */
    fun validate(rawName: String, existingNames: Collection<String> = emptyList()): ValidationResult {
        val name = rawName.trim()
        return when {
            name.isEmpty() -> ValidationResult.Invalid("Project name is required.")
            name.length < MIN_LENGTH -> ValidationResult.Invalid("Use at least $MIN_LENGTH characters.")
            name.length > MAX_LENGTH -> ValidationResult.Invalid("Use at most $MAX_LENGTH characters.")
            !allowed.matches(name) ->
                ValidationResult.Invalid("Start with a letter or digit; use only letters, digits, space, - _ .")
            existingNames.any { it.trim().equals(name, ignoreCase = true) } ->
                ValidationResult.Invalid("A project with this name already exists.")
            else -> ValidationResult.Valid
        }
    }
}

object PackageNameValidator {
    private val segment = Regex("^[a-z][a-z0-9_]*$")

    private val reservedWords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null",
    )

    fun validate(rawPackage: String): ValidationResult {
        val value = rawPackage.trim()
        if (value.isEmpty()) return ValidationResult.Invalid("Package name is required.")
        val parts = value.split('.')
        return when {
            parts.size < 2 -> ValidationResult.Invalid("Use at least two segments, e.g. com.example.")
            parts.any { it.isEmpty() } -> ValidationResult.Invalid("Package name has an empty segment.")
            parts.any { !segment.matches(it) } ->
                ValidationResult.Invalid("Segments must be lowercase, start with a letter, and use letters, digits or _.")
            parts.any { it in reservedWords } -> ValidationResult.Invalid("Package name contains a reserved word.")
            value.length > 120 -> ValidationResult.Invalid("Package name is too long.")
            else -> ValidationResult.Valid
        }
    }
}
