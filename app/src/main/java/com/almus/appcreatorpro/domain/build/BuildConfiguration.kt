package com.almus.appcreatorpro.domain.build

/** What the user wants the cloud build to produce. */
enum class BuildVariant(val id: String, val label: String, val summary: String) {
    DEBUG_APK("debug_apk", "Debug APK", "For testing on your own phone. Signed with the standard Android debug key. Not for publishing."),
    RELEASE_APK("release_apk", "Release APK", "A release build signed with your own release key, to install directly."),
    RELEASE_AAB("release_aab", "Release AAB", "An Android App Bundle signed with your own release key, to upload to Google Play."),
    CUSTOM("custom", "Custom", "Choose the output and the build type yourself."),
    ;

    companion object {
        /** The only Gradle tasks the app ever sends. The workflow accepts exactly these four. */
        val ALLOWED_TASKS: List<String> = listOf("assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease")

        fun fromId(id: String?): BuildVariant? = entries.firstOrNull { it.id == id }
    }
}

/** The file type of a custom build. */
enum class OutputFormat(val id: String, val label: String) {
    APK("apk", "APK (install directly)"),
    AAB("aab", "AAB (Android App Bundle)"),
    ;

    companion object {
        fun fromId(id: String?): OutputFormat? = entries.firstOrNull { it.id == id }
    }
}

/** Optional release options. null means "keep the project's own value". */
data class BuildOptions(
    val minify: Boolean? = null,
    val shrinkResources: Boolean? = null,
)

/**
 * Everything the user chose for a build. Holds no secret: the signing key is referenced only by its id.
 * Null number/text fields mean "keep what the project already says".
 */
data class BuildConfiguration(
    val variant: BuildVariant = BuildVariant.DEBUG_APK,
    val customOutput: OutputFormat = OutputFormat.APK,
    val customRelease: Boolean = false,
    val customSign: Boolean = true,
    val applicationId: String? = null,
    val versionName: String? = null,
    val versionCode: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val options: BuildOptions = BuildOptions(),
    val signingProfileId: String? = null,
) {
    /** The output file type this configuration produces. */
    val outputFormat: OutputFormat
        get() = when (variant) {
            BuildVariant.RELEASE_AAB -> OutputFormat.AAB
            BuildVariant.CUSTOM -> customOutput
            else -> OutputFormat.APK
        }

    val isRelease: Boolean
        get() = when (variant) {
            BuildVariant.DEBUG_APK -> false
            BuildVariant.RELEASE_APK, BuildVariant.RELEASE_AAB -> true
            BuildVariant.CUSTOM -> customRelease
        }

    /** One of [BuildVariant.ALLOWED_TASKS]. */
    val gradleTask: String
        get() = (if (outputFormat == OutputFormat.AAB) "bundle" else "assemble") + (if (isRelease) "Release" else "Debug")

    /** True when the finished release file must be signed with the user's own key in the cloud. */
    val needsReleaseSigning: Boolean
        get() = when (variant) {
            BuildVariant.DEBUG_APK -> false
            BuildVariant.RELEASE_APK, BuildVariant.RELEASE_AAB -> true
            BuildVariant.CUSTOM -> customRelease && customSign
        }

    /** Short name for lists and notifications, e.g. "Release AAB". */
    fun shortLabel(): String = when (variant) {
        BuildVariant.CUSTOM ->
            "Custom " + (if (isRelease) "release" else "debug") + " " + outputFormat.name +
                (if (needsReleaseSigning) "" else if (isRelease) " (unsigned)" else "")
        else -> variant.label
    }

    companion object {
        val DEFAULT = BuildConfiguration()
    }
}
