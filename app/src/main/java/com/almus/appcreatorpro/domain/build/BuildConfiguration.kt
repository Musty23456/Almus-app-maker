package com.almus.appcreatorpro.domain.build

/** Optional switches; null means "keep what the project's own build file says". */
data class BuildOptions(
    val minify: Boolean? = null,
    val shrinkResources: Boolean? = null,
)

/**
 * What the user asked a build to produce. Holds no secret: the signing key is referenced by [signingProfileId] only.
 * Empty overrides (null) are not applied; the project's own values stay.
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
    val output: OutputFormat
        get() = when (variant) {
            BuildVariant.DEBUG_APK, BuildVariant.RELEASE_APK -> OutputFormat.APK
            BuildVariant.RELEASE_AAB -> OutputFormat.AAB
            BuildVariant.CUSTOM -> customOutput
        }

    val isRelease: Boolean
        get() = when (variant) {
            BuildVariant.DEBUG_APK -> false
            BuildVariant.RELEASE_APK, BuildVariant.RELEASE_AAB -> true
            BuildVariant.CUSTOM -> customRelease
        }

    val isBundle: Boolean get() = output == OutputFormat.AAB

    /** True when the finished file must be signed with the user's own release key. */
    val needsReleaseSigning: Boolean
        get() = when (variant) {
            BuildVariant.DEBUG_APK -> false
            BuildVariant.RELEASE_APK, BuildVariant.RELEASE_AAB -> true
            BuildVariant.CUSTOM -> customRelease && customSign
        }

    /** One of [BuildVariant.ALLOWED_TASKS]. */
    val gradleTask: String
        get() = (if (isBundle) "bundle" else "assemble") + (if (isRelease) "Release" else "Debug")

    /** Short name for lists and notifications, for example "Release AAB". */
    fun shortLabel(): String = when (variant) {
        BuildVariant.CUSTOM -> "Custom " + (if (isRelease) "release " else "debug ") + output.name
        else -> variant.label
    }

    companion object {
        val DEFAULT = BuildConfiguration(BuildVariant.DEBUG_APK)
    }
}
