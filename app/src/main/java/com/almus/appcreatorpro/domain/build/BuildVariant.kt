package com.almus.appcreatorpro.domain.build

/** What a build produces: an installable APK or an Android App Bundle (for Google Play). */
enum class OutputFormat(val id: String, val label: String) {
    APK("apk", "APK (install on a phone)"),
    AAB("aab", "AAB (Android App Bundle for Google Play)"),
    ;

    companion object {
        fun fromId(id: String?): OutputFormat? = entries.firstOrNull { it.id == id }
    }
}

/** The build types the user can pick. [CUSTOM] lets the user choose output and release/debug separately. */
enum class BuildVariant(
    val id: String,
    val label: String,
    val summary: String,
) {
    DEBUG_APK("debug_apk", "Debug APK", "Quick test build. Signed with a temporary debug key. Installs on any phone."),
    RELEASE_APK("release_apk", "Release APK", "Optimised build signed with your own release key."),
    RELEASE_AAB("release_aab", "Release AAB", "Android App Bundle signed with your release key, for Google Play."),
    CUSTOM("custom", "Custom", "Choose the output and the build type yourself."),
    ;

    companion object {
        /** The only Gradle tasks the app ever sends. The cloud workflow accepts exactly these four. */
        val ALLOWED_TASKS: List<String> = listOf("assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease")

        fun fromId(id: String?): BuildVariant? = entries.firstOrNull { it.id == id }
    }
}
