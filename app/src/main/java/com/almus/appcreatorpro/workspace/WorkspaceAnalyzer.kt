package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.ai.AiProjectFiles
import com.almus.appcreatorpro.ai.ContextPolicy
import com.almus.appcreatorpro.ai.DependencyExtractor
import com.almus.appcreatorpro.ai.SecretScanner

/** A short, factual summary of a project's shape. Built from file names and build files only: no source text is included. */
data class WorkspaceDigest(
    val fileCount: Int,
    val kotlinFiles: Int,
    val javaFiles: Int,
    val xmlLayouts: Int,
    val composeFiles: Int,
    /** For example "Jetpack Compose", "XML layouts (Views)", "Compose and XML layouts", "no Android UI found". */
    val uiToolkit: String,
    val architectureHints: List<String>,
    /** The files that explain a project best (README, manifest, build files, main screen, theme). At most [MAX_KEY_FILES]. */
    val keyFiles: List<String>,
    /** Files that look like UI (screens, layouts, themes). At most [MAX_UI_FILES]. */
    val uiFiles: List<String>,
) {
    fun render(): String = buildString {
        append("PROJECT DIGEST (computed by the app from file names and build files):\n")
        append("- files: ").append(fileCount).append(" (Kotlin ").append(kotlinFiles).append(", Java ").append(javaFiles)
            .append(", XML layouts ").append(xmlLayouts).append(")\n")
        append("- UI toolkit: ").append(uiToolkit).append('\n')
        if (architectureHints.isNotEmpty()) append("- architecture hints: ").append(architectureHints.joinToString("; ")).append('\n')
        if (uiFiles.isNotEmpty()) {
            append("- UI files:\n")
            uiFiles.forEach { append("  - ").append(it).append('\n') }
        }
    }

    companion object {
        const val MAX_KEY_FILES = 6
        const val MAX_UI_FILES = 40
    }
}

object WorkspaceAnalyzer {
    private const val MAX_KT_TO_SCAN = 40

    private val readmeRegex = Regex("^readme(\\.[a-z]+)?$", RegexOption.IGNORE_CASE)

    /** Which files [analyze] needs to read: build files, the manifest and a bounded number of Kotlin files. */
    fun filesToRead(paths: List<String>): List<String> {
        val visible = visible(paths)
        val build = visible.filter { isBuildFile(it) }.take(8)
        val manifest = visible.filter { it.endsWith("AndroidManifest.xml") }.take(2)
        val kotlin = visible.filter { it.endsWith(".kt") }
            .sortedBy { if (looksLikeUi(it)) 0 else 1 }
            .take(MAX_KT_TO_SCAN)
        return (build + manifest + kotlin).distinct()
    }

    /** Pure: [read] returns a file's text or null. Never throws for unreadable files. */
    fun analyze(paths: List<String>, read: (String) -> String?): WorkspaceDigest {
        val visible = visible(paths)
        val kotlin = visible.count { it.endsWith(".kt") }
        val java = visible.count { it.endsWith(".java") }
        val layouts = visible.count { it.contains("/res/layout") && it.endsWith(".xml") }

        val buildTexts = visible.filter { isBuildFile(it) }.take(8).mapNotNull { read(it) }
        val dependencies = DependencyExtractor.extract(buildTexts).map { it.lowercase() }
        fun dep(vararg parts: String) = dependencies.any { d -> parts.any { d.contains(it) } }

        val composeFiles = visible.filter { it.endsWith(".kt") }.take(MAX_KT_TO_SCAN * 2).count { path ->
            read(path)?.contains("androidx.compose") == true
        }
        val usesCompose = composeFiles > 0 || dep("androidx.compose", "compose")
        val usesViews = layouts > 0
        val toolkit = when {
            usesCompose && usesViews -> "Compose and XML layouts (mixed)"
            usesCompose -> "Jetpack Compose"
            usesViews -> "XML layouts (Views)"
            visible.any { it.endsWith(".html") } -> "HTML / CSS / JavaScript"
            else -> "no Android UI found"
        }

        val hints = ArrayList<String>()
        if (dep("hilt", "dagger")) hints += "dependency injection (Hilt/Dagger)"
        if (dep("androidx.room")) hints += "Room database"
        if (dep("retrofit")) hints += "Retrofit networking"
        if (dep("okhttp")) hints += "OkHttp"
        if (dep("androidx.navigation")) hints += "Jetpack Navigation"
        if (dep("com.google.android.material")) hints += "Material Components"
        if (dep("androidx.compose.material3")) hints += "Material 3 (Compose)"
        if (dep("coil", "glide", "picasso")) hints += "image loading library"
        if (dep("kotlinx-coroutines", "kotlinx.coroutines")) hints += "Kotlin coroutines"
        if (visible.any { it.endsWith("ViewModel.kt") || it.endsWith("ViewModel.java") }) hints += "ViewModel classes"
        if (visible.any { it.contains("/test/") && (it.endsWith(".kt") || it.endsWith(".java")) }) hints += "has unit tests"

        val uiFiles = visible.filter { looksLikeUi(it) }.take(WorkspaceDigest.MAX_UI_FILES)
        return WorkspaceDigest(
            fileCount = visible.size,
            kotlinFiles = kotlin,
            javaFiles = java,
            xmlLayouts = layouts,
            composeFiles = composeFiles,
            uiToolkit = toolkit,
            architectureHints = hints,
            keyFiles = keyFiles(visible),
            uiFiles = uiFiles,
        )
    }

    /** Reads what [filesToRead] asks for through [files] and analyzes it. Unreadable files are skipped. */
    suspend fun analyze(files: AiProjectFiles): WorkspaceDigest {
        val paths = try { files.list() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
        val texts = HashMap<String, String>()
        for (p in filesToRead(paths)) {
            try {
                texts[p] = files.read(p)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // skipped: too large or not text
            }
        }
        return analyze(paths) { texts[it] }
    }

    private fun visible(paths: List<String>): List<String> =
        paths.filter { !ContextPolicy.isHiddenPath(it) && !SecretScanner.isSensitivePath(it) }

    private fun isBuildFile(path: String): Boolean {
        val name = path.substringAfterLast('/')
        return name == "build.gradle" || name == "build.gradle.kts" || name == "libs.versions.toml"
    }

    private fun looksLikeUi(path: String): Boolean {
        val lower = path.lowercase()
        val name = lower.substringAfterLast('/')
        return lower.contains("/res/layout") || lower.contains("/ui/") || lower.contains("/theme") ||
            lower.endsWith("/res/values/colors.xml") || lower.endsWith("/res/values/themes.xml") || lower.endsWith("/res/values/styles.xml") ||
            name.endsWith("screen.kt") || name.endsWith("activity.kt") || name.endsWith("activity.java") ||
            name.endsWith("fragment.kt") || name.endsWith("fragment.java") || name.endsWith("theme.kt") || name == "index.html"
    }

    /** Ordered by usefulness: README, settings, app build file, manifest, main screen, theme. */
    private fun keyFiles(visible: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        visible.filter { !it.contains('/') && readmeRegex.matches(it) }.sorted().firstOrNull()?.let { out += it }
        visible.filter { it.substringAfterLast('/').startsWith("settings.gradle") }.minByOrNull { it.count { c -> c == '/' } }?.let { out += it }
        visible.filter { it.substringAfterLast('/').let { n -> n == "build.gradle.kts" || n == "build.gradle" } && it.contains('/') }
            .minByOrNull { it.count { c -> c == '/' } }?.let { out += it }
        visible.filter { it.endsWith("src/main/AndroidManifest.xml") || it == "AndroidManifest.xml" }.minByOrNull { it.length }?.let { out += it }
        visible.filter { it.substringAfterLast('/').startsWith("MainActivity.") }.minByOrNull { it.length }?.let { out += it }
        visible.filter { it.substringAfterLast('/').equals("Theme.kt", ignoreCase = true) }.minByOrNull { it.length }?.let { out += it }
        visible.firstOrNull { it == "index.html" }?.let { out += it }
        return out.take(WorkspaceDigest.MAX_KEY_FILES)
    }
}
