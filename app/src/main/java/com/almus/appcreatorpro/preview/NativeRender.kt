package com.almus.appcreatorpro.preview

/**
 * The honest "native" strategy for Android: render with Android's own layout library (Layoutlib), the engine behind
 * Android Studio's previews. Layoutlib needs a JVM with the Android SDK platform data; it cannot run inside this app.
 * The practical route is a CLOUD job (the app already runs cloud builds): a screenshot-test tool renders the project's
 * @Preview functions and layouts to PNG images, which the app then shows. Tools that do this:
 *  - Paparazzi (Layoutlib, runs on a plain JVM, works for Views and Compose),
 *  - Google's Compose Preview Screenshot Testing (Layoutlib, experimental, Compose @Preview only, needs a screenshotTest source set),
 *  - Roborazzi (Robolectric native graphics instead of Layoutlib).
 * This phase does NOT run such a job. It checks what the project still needs, and says clearly that the job itself is
 * not implemented ([NativeRenderProvider] is the extension point).
 */
data class NativeReadinessItem(val label: String, val ok: Boolean, val detail: String)

data class NativeRenderReadiness(val items: List<NativeReadinessItem>, val renderer: String?) {
    /** The project side is prepared. It does not mean a cloud render is available: see [NativeRenderProvider.availability]. */
    val projectReady: Boolean get() = items.filter { it.label != CLOUD_JOB }.all { it.ok }

    companion object { const val CLOUD_JOB = "ALMUS cloud render job" }
}

object NativeRenderReadinessChecker {
    /** @param texts Gradle files and Kotlin sources (path -> text); @param paths every project path. */
    fun check(texts: Map<String, String>, paths: Set<String>): NativeRenderReadiness {
        val items = ArrayList<NativeReadinessItem>()
        val gradle = texts.filter { it.key.endsWith(".gradle") || it.key.endsWith(".gradle.kts") || it.key.endsWith(".toml") || it.key.endsWith("gradle.properties") }.values.joinToString("\n")
        val kotlin = texts.filter { it.key.endsWith(".kt") }.values
        val usesCompose = gradle.contains("compose") || kotlin.any { it.contains("@Composable") }
        val layouts = paths.count { PreviewDetector.isLayout(it) }
        val previews = kotlin.sumOf { src -> Regex("@Preview\\b").findAll(src).count() }

        items += NativeReadinessItem("UI to render", usesCompose || layouts > 0, if (usesCompose || layouts > 0) "Compose: ${if (usesCompose) "yes" else "no"}, XML layouts: $layouts" else "No Compose code and no XML layouts were found.")
        if (usesCompose) items += NativeReadinessItem("@Preview functions", previews > 0, if (previews > 0) "$previews found" else "Compose screens need @Preview functions for the renderers to pick up.")

        val renderer = when {
            gradle.contains("app.cash.paparazzi") -> "Paparazzi"
            gradle.contains("com.android.compose.screenshot") || gradle.contains("enableScreenshotTest") -> "Compose Preview Screenshot Testing"
            gradle.contains("roborazzi") -> "Roborazzi"
            else -> null
        }
        items += NativeReadinessItem(
            "Screenshot renderer in the build", renderer != null,
            renderer?.let { "$it is configured." } ?: "None of Paparazzi, Compose Preview Screenshot Testing or Roborazzi is set up in the Gradle files.",
        )
        val hasTestSources = paths.any { it.contains("/src/test/") || it.contains("/src/screenshotTest/") }
        items += NativeReadinessItem("Test source set for snapshots", hasTestSources, if (hasTestSources) "Found." else "No src/test or src/screenshotTest sources.")
        items += NativeReadinessItem(NativeRenderReadiness.CLOUD_JOB, false, "Not implemented in this version: ALMUS cannot start a cloud render yet.")
        return NativeRenderReadiness(items, renderer)
    }
}

sealed interface NativeRenderAvailability {
    data object Available : NativeRenderAvailability
    data class Unavailable(val reason: String) : NativeRenderAvailability
}

/** The seam for a future cloud renderer. The default says it is not there; nothing else in the app pretends otherwise. */
interface NativeRenderProvider {
    val name: String
    fun availability(): NativeRenderAvailability
}

object UnavailableNativeRender : NativeRenderProvider {
    override val name = "Cloud Layoutlib render"
    override fun availability(): NativeRenderAvailability =
        NativeRenderAvailability.Unavailable("Not implemented yet. The project needs a screenshot renderer (see the readiness list), and ALMUS needs a cloud job that runs it and returns the images.")
}
