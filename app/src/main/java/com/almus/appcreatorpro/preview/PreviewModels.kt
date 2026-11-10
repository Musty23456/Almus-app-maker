package com.almus.appcreatorpro.preview

/**
 * Phase 20: live preview. Plain Kotlin (no Android classes) so the logic is unit-testable on a JVM.
 *
 * The most important idea in this package: every preview says HOW it was produced ([PreviewFidelity]). A web page is
 * run by a real web engine. Android layouts and Compose code are only DRAWN FROM THE SOURCE by ALMUS: that is an
 * approximation, never the Android runtime, and the screen always says so.
 */
enum class PreviewFidelity(val label: String, val note: String) {
    WEB_ENGINE(
        "Web engine",
        "Run by Android System WebView (a Chromium-based engine). It is a real web runtime, but results can differ from a desktop browser or another WebView version.",
    ),
    APPROXIMATE(
        "Approximation",
        "Drawn by ALMUS from the source. It is NOT rendered by the Android runtime: real fonts, themes, spacing, system widgets and many attributes differ.",
    ),
    NATIVE_RENDER(
        "Native render",
        "Rendered by Android's own layout library (Layoutlib) in a cloud job. Closest to Android Studio's preview, still not a physical device.",
    ),
    UNAVAILABLE("Not available", "No preview engine exists for this kind of project yet."),
}

enum class PreviewKind(val label: String, val fidelity: PreviewFidelity) {
    WEB("Web page", PreviewFidelity.WEB_ENGINE),
    ANDROID_XML("Android XML layout", PreviewFidelity.APPROXIMATE),
    ANDROID_COMPOSE("Jetpack Compose", PreviewFidelity.APPROXIMATE),
    FLUTTER("Flutter", PreviewFidelity.UNAVAILABLE),
}

/** What the user can preview. [path] is the project file; for Compose [function] names the @Composable. */
data class PreviewTarget(val kind: PreviewKind, val path: String, val label: String, val function: String? = null) {
    val id: String get() = kind.name + ":" + path + (function?.let { "#$it" } ?: "")
}

enum class DeviceCategory { PHONE, FOLDABLE, TABLET, DESKTOP, CUSTOM }
enum class Cutout { NONE, PUNCH_HOLE, NOTCH }

/**
 * A generic device in density-independent pixels. The sizes are typical logical sizes of device classes, not specific
 * products, and the frame is a plain drawn bezel (no manufacturer artwork).
 */
data class DeviceProfile(
    val id: String,
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val category: DeviceCategory,
    val cornerDp: Int = 28,
    val statusBarDp: Int = 24,
    val navBarDp: Int = 24,
    val cutout: Cutout = Cutout.PUNCH_HOLE,
)

object DeviceCatalog {
    const val MIN_DP = 200
    const val MAX_DP = 3000

    val all: List<DeviceProfile> = listOf(
        DeviceProfile("phone-small", "Small phone", 360, 640, DeviceCategory.PHONE, cornerDp = 20, cutout = Cutout.NONE),
        DeviceProfile("phone-compact", "Compact phone", 360, 800, DeviceCategory.PHONE),
        DeviceProfile("phone-medium", "Phone", 412, 915, DeviceCategory.PHONE),
        DeviceProfile("phone-large", "Large phone", 430, 932, DeviceCategory.PHONE, cutout = Cutout.NOTCH),
        DeviceProfile("foldable-open", "Foldable (unfolded)", 673, 841, DeviceCategory.FOLDABLE, cornerDp = 24),
        DeviceProfile("tablet-7", "Tablet 7 inch", 600, 960, DeviceCategory.TABLET, cornerDp = 16, cutout = Cutout.NONE, navBarDp = 32),
        DeviceProfile("tablet-10", "Tablet 10 inch", 800, 1280, DeviceCategory.TABLET, cornerDp = 16, cutout = Cutout.NONE, navBarDp = 32),
        DeviceProfile("desktop", "Desktop / responsive", 1280, 800, DeviceCategory.DESKTOP, cornerDp = 0, statusBarDp = 0, navBarDp = 0, cutout = Cutout.NONE),
    )

    val default: DeviceProfile get() = all.first { it.id == "phone-medium" }

    fun byId(id: String?): DeviceProfile? = all.firstOrNull { it.id == id }

    fun custom(widthDp: Int, heightDp: Int): DeviceProfile {
        val w = widthDp.coerceIn(MIN_DP, MAX_DP)
        val h = heightDp.coerceIn(MIN_DP, MAX_DP)
        return DeviceProfile("custom", "Custom ${w}x$h", w, h, DeviceCategory.CUSTOM, cornerDp = 0, statusBarDp = 0, navBarDp = 0, cutout = Cutout.NONE)
    }
}

/**
 * How the preview is shown. [allowInternet] is OFF by default: a preview cannot reach the network unless the user turns
 * it on for this session (see [PreviewRequestPolicy] for what stays blocked even then).
 */
data class PreviewConfig(
    val device: DeviceProfile = DeviceCatalog.default,
    val landscape: Boolean = false,
    val dark: Boolean = false,
    /** null = fit to the screen. */
    val zoomPercent: Int? = null,
    val fontScale: Float = 1f,
    val showFrame: Boolean = true,
    val allowInternet: Boolean = false,
    val autoRefresh: Boolean = true,
) {
    val widthDp: Int get() = if (landscape) device.heightDp else device.widthDp
    val heightDp: Int get() = if (landscape) device.widthDp else device.heightDp

    /** Height left for the app's own content once the status and navigation bars of the frame are taken off. */
    val contentHeightDp: Int
        get() = if (showFrame) (heightDp - device.statusBarDp - device.navBarDp).coerceAtLeast(100) else heightDp

    fun withDevice(d: DeviceProfile) = copy(device = d)
    fun withCustomSize(w: Int, h: Int) = copy(device = DeviceCatalog.custom(w, h), landscape = false)
    fun withFontScale(s: Float) = copy(fontScale = s.coerceIn(0.85f, 2.0f))
    fun withZoom(percent: Int?) = copy(zoomPercent = percent?.coerceIn(25, 300))
}

enum class ConsoleLevel { DEBUG, LOG, INFO, WARN, ERROR }

enum class ConsoleOrigin(val label: String) {
    PAGE("page"), SCRIPT_ERROR("script error"), RESOURCE("resource"), BLOCKED("blocked"), PARSE("parse"), SYSTEM("preview"),
}

/** One line of the preview console. [path] and [line] point into the project when known, so the editor can jump there. */
data class ConsoleEntry(
    val atMillis: Long,
    val level: ConsoleLevel,
    val origin: ConsoleOrigin,
    val message: String,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    /** How many identical lines in a row this entry stands for. */
    val repeat: Int = 1,
)
