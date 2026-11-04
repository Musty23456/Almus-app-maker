package com.almus.appcreatorpro.domain.build

/** What file a build produces. */
enum class OutputFormat(val id: String, val label: String) {
    APK("apk", "APK (install on a phone)"),
    AAB("aab", "AAB (Google Play bundle)"),
    ;

    companion object {
        fun fromId(id: String?): OutputFormat? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The build types the user can pick. Each one maps to ONE Gradle task from [ALLOWED_TASKS]; the workflow accepts
 * exactly the same list, so the app can never ask for a task the workflow would refuse.
 */
enum class BuildVariant(
    val id: String,
    val label: String,
    val summary: String,
    /** Null for [CUSTOM]: its task depends on the output and release choices in [BuildConfiguration]. */
    val gradleTask: String?,
) {
    DEBUG_APK("debug_apk", "Debug APK", "Quick test build. Signed with the Android debug key. Not for the store.", "assembleDebug"),
    RELEASE_APK("release_apk", "Release APK", "Optimised build, signed with your release key. Can be installed directly.", "assembleRelease"),
    RELEASE_AAB("release_aab", "Release AAB", "App bundle for Google Play, signed with your release key.", "bundleRelease"),
    CUSTOM("custom", "Custom", "Choose the output, the release type and whether to sign yourself.", null),
    ;

    companion object {
        /** The only Gradle tasks the app sends and the workflow accepts. */
        val ALLOWED_TASKS: List<String> = listOf("assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease")

        fun fromId(id: String?): BuildVariant? = entries.firstOrNull { it.id == id }
    }
}
