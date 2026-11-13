package com.almus.appcreatorpro.explorer

/**
 * Configuration files that break the project when they disappear or move. The explorer never deletes or renames them
 * on a single tap: deleting needs the file name typed in, renaming needs an extra confirmation.
 */
data class ProtectionInfo(val important: Boolean, val reason: String? = null)

object ProtectedFiles {
    private val NOT_IMPORTANT = ProtectionInfo(false)

    private val anywhere = mapOf(
        "build.gradle" to "Gradle build script",
        "build.gradle.kts" to "Gradle build script",
        "settings.gradle" to "Gradle settings",
        "settings.gradle.kts" to "Gradle settings",
        "gradle.properties" to "Gradle properties",
        "androidmanifest.xml" to "Android manifest",
        "proguard-rules.pro" to "ProGuard / R8 rules",
        "consumer-rules.pro" to "ProGuard / R8 rules",
        "libs.versions.toml" to "Gradle version catalog",
        "gradle-wrapper.properties" to "Gradle wrapper settings",
    )

    /** Only protected when they sit in the project root. */
    private val rootOnly = mapOf(
        "package.json" to "npm package file",
        "tsconfig.json" to "TypeScript config",
        "pubspec.yaml" to "Dart/Flutter package file",
        "pom.xml" to "Maven build file",
        "requirements.txt" to "Python requirements",
        "pyproject.toml" to "Python project file",
    )

    fun check(path: String): ProtectionInfo {
        val name = path.substringAfterLast('/').lowercase()
        anywhere[name]?.let { return ProtectionInfo(true, it) }
        if (!path.contains('/')) rootOnly[name]?.let { return ProtectionInfo(true, it) }
        if (path.startsWith(".github/workflows/") && (name.endsWith(".yml") || name.endsWith(".yaml"))) {
            return ProtectionInfo(true, "CI workflow")
        }
        return NOT_IMPORTANT
    }

    fun isProtected(path: String): Boolean = check(path).important

    fun protectedIn(paths: Collection<String>): List<String> = paths.filter { isProtected(it) }.sorted()
}
