package com.almus.appcreatorpro.domain.build

/** Optional switches. `null` means "keep what the project's own build file says". */
data class BuildOptions(
    val minify: Boolean? = null,
    val shrinkResources: Boolean? = null,
) {
    val isDefault: Boolean get() = minify == null && shrinkResources == null
}

/**
 * What the user asked a build to produce. Holds no secret: the signing key is referenced by [signingProfileId] only.
 * Every override is optional; an empty configuration is the plain debug APK build of earlier phases.
 */
data class BuildConfiguration(
    val variant: BuildVariant = BuildVariant.DEBUG_APK,
    /** Only used when [variant] is [BuildVariant.CUSTOM]. */
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

    val isBundle: Boolean get() = output == OutputFormat.AAB

    /** The Gradle task for this configuration. Always one of [BuildVariant.ALLOWED_TASKS]. */
    val gradleTask: String
        get() = variant.gradleTask ?: when (output) {
            OutputFormat.APK -> if (isRelease) "assembleRelease" else "assembleDebug"
            OutputFormat.AAB -> if (isRelease) "bundleRelease" else "bundleDebug"
        }

    /** Whether the cloud build signs the result with the user's key (only release builds are). */
    val needsReleaseSigning: Boolean
        get() = isRelease && (variant != BuildVariant.CUSTOM || customSign)

    /** True when anything differs from what the project's own files say. */
    val hasOverrides: Boolean
        get() = applicationId != null || versionName != null || versionCode != null ||
            minSdk != null || targetSdk != null || !options.isDefault

    /** True when the build needs workflow version 2 (version 1 only builds plain debug APKs). */
    val needsAdvancedWorkflow: Boolean
        get() = gradleTask != "assembleDebug" || hasOverrides

    /** Short text for lists and notifications, for example "Release AAB" or "Custom (Debug AAB)". */
    fun shortLabel(): String = when (variant) {
        BuildVariant.CUSTOM -> {
            val kind = (if (isRelease) "Release " else "Debug ") + (if (isBundle) "AAB" else "APK")
            val signed = if (isRelease && !customSign) ", unsigned" else ""
            "Custom ($kind$signed)"
        }
        else -> variant.label
    }

    companion object {
        val DEFAULT = BuildConfiguration()
    }
}
