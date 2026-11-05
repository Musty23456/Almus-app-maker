package com.almus.appcreatorpro.domain.validation

/** Validates file paths inside a project: relative, '/' separated, no traversal. */
object FilePathValidator {
    const val MAX_PATH_LENGTH = 200
    const val MAX_SEGMENT_LENGTH = 80
    const val MAX_DEPTH = 12

    /** Suffix used for temporary files while saving; users may not create names ending with it. */
    const val RESERVED_SUFFIX = ".almus-tmp"

    private val segmentChars = Regex("^[A-Za-z0-9_.\\- ]+$")

    fun validate(rawPath: String): ValidationResult {
        val path = rawPath
        if (path.isBlank()) return ValidationResult.Invalid("File name is required.")
        if (path.length > MAX_PATH_LENGTH) return ValidationResult.Invalid("Path is too long.")
        if (path.startsWith("/")) return ValidationResult.Invalid("Use a relative path (no leading /).")
        if (path.endsWith("/")) return ValidationResult.Invalid("A file name cannot end with /.")
        val segments = path.split('/')
        if (segments.size > MAX_DEPTH) return ValidationResult.Invalid("Folders are nested too deeply.")
        for (segment in segments) {
            when {
                segment.isEmpty() -> return ValidationResult.Invalid("Path has an empty folder or file name.")
                segment == "." || segment == ".." -> return ValidationResult.Invalid("'.' and '..' are not allowed.")
                segment != segment.trim() -> return ValidationResult.Invalid("Names cannot start or end with a space.")
                segment.length > MAX_SEGMENT_LENGTH -> return ValidationResult.Invalid("A name is too long.")
                !segmentChars.matches(segment) ->
                    return ValidationResult.Invalid("Use only letters, digits, space, - _ . and / in names.")
                segment.endsWith(RESERVED_SUFFIX) -> return ValidationResult.Invalid("That name is reserved.")
            }
        }
        return ValidationResult.Valid
    }
}
