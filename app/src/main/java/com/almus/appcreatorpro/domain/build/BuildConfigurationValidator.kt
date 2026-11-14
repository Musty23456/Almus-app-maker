package com.almus.appcreatorpro.domain.build

/**
 * The same rules the workflow checks again in the cloud. Every function returns null when the value is fine, or a
 * short message for the user. An empty value is always fine: it means "keep the project's value".
 */
object BuildConfigurationValidator {
    private val APPLICATION_ID = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val VERSION_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]{0,49}$")
    const val MAX_VERSION_CODE = 2_100_000_000
    const val MIN_SDK_LOWEST = 21
    const val SDK_HIGHEST = 99

    fun validateApplicationId(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return if (APPLICATION_ID.matches(value)) null
        else "Application ID needs at least two parts separated by dots, such as com.example.app (letters, digits and _ only; each part starts with a letter)."
    }

    fun validateVersionName(value: String?): String? {
        if (value.isNullOrEmpty()) return null
        return if (VERSION_NAME.matches(value)) null
        else "Version name may use letters, digits and . _ + - only (up to 50 characters)."
    }

    fun validateVersionCode(value: Int?): String? {
        if (value == null) return null
        return if (value in 1..MAX_VERSION_CODE) null else "Version code must be between 1 and $MAX_VERSION_CODE."
    }

    fun validateMinSdk(value: Int?): String? {
        if (value == null) return null
        return if (value in MIN_SDK_LOWEST..SDK_HIGHEST) null else "Minimum SDK must be between $MIN_SDK_LOWEST and $SDK_HIGHEST."
    }

    fun validateTargetSdk(value: Int?, minSdk: Int?): String? {
        if (value == null) return null
        if (value !in MIN_SDK_LOWEST..SDK_HIGHEST) return "Target SDK must be between $MIN_SDK_LOWEST and $SDK_HIGHEST."
        if (minSdk != null && value < minSdk) return "Target SDK cannot be lower than the minimum SDK."
        return null
    }

    /** All problems of a whole configuration (used before a build starts). */
    fun validate(config: BuildConfiguration): List<String> = buildList {
        validateApplicationId(config.applicationId)?.let(::add)
        validateVersionName(config.versionName)?.let(::add)
        validateVersionCode(config.versionCode)?.let(::add)
        validateMinSdk(config.minSdk)?.let(::add)
        validateTargetSdk(config.targetSdk, config.minSdk)?.let(::add)
        if (config.options.shrinkResources == true && config.options.minify == false) {
            add("Shrink resources needs code shrinking (minify) to be on.")
        }
    }
}
