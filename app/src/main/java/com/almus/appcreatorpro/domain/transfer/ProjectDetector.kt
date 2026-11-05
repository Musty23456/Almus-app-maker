package com.almus.appcreatorpro.domain.transfer

import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.validation.PackageNameValidator

/** Result of looking at an unpacked project. [type] is null when it is not a supported project ([problem] says why). */
data class ProjectDetection(
    val type: ProjectType?,
    val reasons: List<String>,
    val problem: String?,
    val packageName: String,
    /** A name found inside the project (Gradle rootProject.name, package.json name), if any. */
    val nameHint: String?,
    val warnings: List<String>,
)

/** Recognizes the project structures the app supports from a list of relative paths. Pure, no I/O besides [readText]. */
object ProjectDetector {
    const val FALLBACK_PACKAGE = "com.example.imported"

    /**
     * Files that mark the top of a project; used to find a wrapper folder inside an archive. index.html is
     * deliberately NOT here: `src/index.html` is a normal structure and `src` must not be stripped.
     */
    val ROOT_MARKERS: Set<String> = setOf(
        "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts",
        "AndroidManifest.xml", "package.json",
    )

    private val gradleNames = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
    private val namespaceRegex = Regex("""namespace\s*=?\s*["']([A-Za-z0-9_.]+)["']""")
    private val applicationIdRegex = Regex("""applicationId\s*=?\s*["']([A-Za-z0-9_.]+)["']""")
    private val manifestPackageRegex = Regex("package\\s*=\\s*\"([A-Za-z0-9_.]+)\"")
    private val rootNameRegex = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""")
    private val packageJsonNameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * @param paths every project file, relative, '/' separated (editable and preserved files alike).
     * @param readText returns the text of a path, or null when it cannot be read as text.
     */
    fun detect(paths: Collection<String>, readText: (String) -> String?): ProjectDetection {
        val reasons = ArrayList<String>()
        val warnings = ArrayList<String>()
        val names = paths.map { it.substringAfterLast('/') }

        // ---- not supported at all -------------------------------------------------------------------------
        if (names.contains("pubspec.yaml")) {
            return unsupported("This looks like a Flutter/Dart project. Flutter projects are not supported yet.")
        }

        // ---- Android ---------------------------------------------------------------------------------------
        val gradlePaths = paths.filter { it.substringAfterLast('/') in gradleNames }.sortedBy { it.count { c -> c == '/' } }
        val gradleTexts = gradlePaths.take(MAX_GRADLE_FILES).mapNotNull { readText(it) }
        val manifestPaths = paths.filter { it == "AndroidManifest.xml" || it.endsWith("/AndroidManifest.xml") }
        val androidPlugin = gradleTexts.any { it.contains("com.android.") || it.contains("android-application") }
        val isAndroid = manifestPaths.isNotEmpty() || androidPlugin

        val nameHint = gradlePaths.firstOrNull { it.substringAfterLast('/').startsWith("settings.gradle") }
            ?.let { readText(it) }
            ?.let { rootNameRegex.find(it)?.groupValues?.get(1) }

        if (isAndroid) {
            val kotlinFiles = paths.count { it.endsWith(".kt") }
            val javaFiles = paths.count { it.endsWith(".java") }
            val kotlinPlugin = gradleTexts.any {
                it.contains("kotlin-android") || it.contains("org.jetbrains.kotlin.android") ||
                    it.contains("kotlin(\"android\")") || it.contains("kotlin.android")
            }
            val type = when {
                kotlinFiles > 0 -> ProjectType.ANDROID_KOTLIN
                javaFiles > 0 && kotlinPlugin -> ProjectType.ANDROID_KOTLIN
                javaFiles > 0 -> ProjectType.ANDROID_JAVA
                kotlinPlugin -> ProjectType.ANDROID_KOTLIN
                else -> null
            }
            if (type == null) {
                return ProjectDetection(
                    type = null,
                    reasons = listOf("Android project structure found"),
                    problem = "This Android project has no Kotlin or Java source files.",
                    packageName = "",
                    nameHint = nameHint,
                    warnings = warnings,
                )
            }
            reasons += "Android project (Gradle / AndroidManifest.xml)"
            reasons += if (type == ProjectType.ANDROID_KOTLIN) {
                if (javaFiles > 0 && kotlinFiles > 0) "Kotlin and Java sources (treated as Kotlin)" else "Kotlin sources or plugin found"
            } else {
                "Java sources found, no Kotlin"
            }
            if (!names.contains("gradlew") && !paths.any { it.endsWith("gradle/wrapper/gradle-wrapper.properties") }) {
                warnings += "No Gradle wrapper found. Cloud builds use the Gradle version chosen in the cloud build settings."
            }
            val pkg = findPackage(gradleTexts, manifestPaths, readText)
            val packageName = if (pkg != null) pkg else {
                warnings += "The package name could not be read from the project, so $FALLBACK_PACKAGE is used. You can ignore this if you do not need cloud builds."
                FALLBACK_PACKAGE
            }
            return ProjectDetection(type, reasons, null, packageName, nameHint, warnings)
        }

        // ---- Web (HTML / CSS / JavaScript) -------------------------------------------------------------------
        val htmlFiles = paths.filter { it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true) }
        val webAssets = paths.filter {
            it.endsWith(".css", ignoreCase = true) || it.endsWith(".js", ignoreCase = true) || it.endsWith(".mjs", ignoreCase = true)
        }
        if (htmlFiles.isNotEmpty() || webAssets.isNotEmpty()) {
            reasons += when {
                htmlFiles.isNotEmpty() && webAssets.isNotEmpty() -> "HTML with CSS / JavaScript files"
                htmlFiles.isNotEmpty() -> "HTML files"
                else -> "CSS / JavaScript files"
            }
            if (htmlFiles.none { it.substringAfterLast('/').equals("index.html", ignoreCase = true) }) {
                warnings += "No index.html was found. The project is imported as a web project anyway."
            }
            if (names.contains("package.json")) {
                reasons += "package.json found"
                warnings += "Only source files are imported; dependency folders such as node_modules are left out."
            }
            val hint = paths.firstOrNull { it == "package.json" }?.let { readText(it) }
                ?.let { packageJsonNameRegex.find(it)?.groupValues?.get(1) }
            return ProjectDetection(ProjectType.WEB, reasons, null, "", hint, warnings)
        }

        return unsupported(
            "No supported project was recognized. Supported: native Android (Kotlin or Java, Gradle) and " +
                "HTML / CSS / JavaScript projects.",
        )
    }

    private fun unsupported(message: String) = ProjectDetection(
        type = null,
        reasons = emptyList(),
        problem = message,
        packageName = "",
        nameHint = null,
        warnings = emptyList(),
    )

    private fun findPackage(gradleTexts: List<String>, manifestPaths: List<String>, readText: (String) -> String?): String? {
        val candidates = ArrayList<String>()
        for (regex in listOf(namespaceRegex, applicationIdRegex)) {
            for (text in gradleTexts) regex.find(text)?.groupValues?.get(1)?.let { candidates += it }
        }
        for (path in manifestPaths.sortedBy { if (it.endsWith("src/main/AndroidManifest.xml")) 0 else 1 }) {
            readText(path)?.let { text -> manifestPackageRegex.find(text)?.groupValues?.get(1)?.let { candidates += it } }
        }
        return candidates.firstOrNull { PackageNameValidator.validate(it).isValid }
    }

    private const val MAX_GRADLE_FILES = 12
}
