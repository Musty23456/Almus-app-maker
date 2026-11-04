package com.almus.appcreatorpro.domain.build

/**
 * Checks the values of a [BuildConfiguration]. Each function returns a user-safe message, or null when the value is
 * fine (an empty value is always fine: it means "keep the project's value"). The workflow checks the same rules again.
 */
object BuildConfigurationValidator {
    const val MIN_SDK_LOWEST = 21
    const val SDK_HIGHEST = 40
    const val MAX_VERSION_CODE = 2_100_000_000

    private val APPLICATION_ID = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val VERSION_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")

    fun validateApplicationId(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        if (value.length > 150) return "The application ID is too long."
        if (!APPLICATION_ID.matches(value)) {
            return "The application ID needs at least two parts separated by dots, for example com.example.app. " +
                "Use letters, digits and underscores; each part starts with a letter."
        }
        return null
    }

    fun validateVersionName(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        if (value.length > 50) return "The version name is too long (50 characters at most)."
        if (!VERSION_NAME.matches(value)) return "The version name may contain letters, digits and . _ + - only."
        return null
    }

    fun validateVersionCode(value: Int?): String? {
        if (value == null) return null
        if (value < 1 || value > MAX_VERSION_CODE) return "The version code must be a whole number from 1 to $MAX_VERSION_CODE."
        return null
    }

    fun validateMinSdk(value: Int?): String? {
        if (value == null) return null
        if (value < MIN_SDK_LOWEST || value > SDK_HIGHEST) return "The minimum SDK must be from $MIN_SDK_LOWEST to $SDK_HIGHEST."
        return null
    }

    fun validateTargetSdk(target: Int?, minSdk: Int?): String? {
        if (target == null) return null
        if (target < MIN_SDK_LOWEST || target > SDK_HIGHEST) return "The target SDK must be from $MIN_SDK_LOWEST to $SDK_HIGHEST."
        if (minSdk != null && target < minSdk) return "The target SDK cannot be lower than the minimum SDK."
        return null
    }

    /** Every problem of [config], in the order of the form. Empty when the configuration can be built. */
    fun validate(config: BuildConfiguration): List<String> = buildList {
        validateApplicationId(config.applicationId)?.let { add(it) }
        validateVersionName(config.versionName)?.let { add(it) }
        validateVersionCode(config.versionCode)?.let { add(it) }
        validateMinSdk(config.minSdk)?.let { add(it) }
        validateTargetSdk(config.targetSdk, config.minSdk)?.let { add(it) }
        if (config.options.shrinkResources == true && config.options.minify == false) {
            add("Shrink resources needs code shrinking (minify) to be on.")
        }
        if (config.gradleTask !in BuildVariant.ALLOWED_TASKS) add("This build type is not supported.")
    }
}
