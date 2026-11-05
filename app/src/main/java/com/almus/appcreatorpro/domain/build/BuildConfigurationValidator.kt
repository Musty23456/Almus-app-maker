package com.almus.appcreatorpro.domain.build

/** Checks build settings before they are saved or sent. Each function returns null when the value is fine. */
object BuildConfigurationValidator {
    const val MIN_SDK_LOWEST = 21
    const val SDK_HIGHEST = 40

    private val applicationIdPattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val versionNamePattern = Regex("^[A-Za-z0-9][A-Za-z0-9._+-]*$")

    fun validateApplicationId(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.length > 150) return "Application ID is too long."
        if (!applicationIdPattern.matches(value)) {
            return "Application ID looks like com.example.app: at least two parts, letters, digits and underscores, each part starting with a letter."
        }
        return null
    }

    fun validateVersionName(value: String?): String? {
        if (value.isNullOrBlank()) return null
        if (value.length > 50) return "Version name is too long."
        if (!versionNamePattern.matches(value)) return "Version name may contain letters, digits, dots, dashes, plus and underscores."
        return null
    }

    fun validateVersionCode(value: Int?): String? {
        if (value == null) return null
        if (value < 1 || value > 2_100_000_000) return "Version code must be between 1 and 2100000000."
        return null
    }

    fun validateMinSdk(value: Int?): String? {
        if (value == null) return null
        if (value < MIN_SDK_LOWEST || value > SDK_HIGHEST) return "Minimum SDK must be between $MIN_SDK_LOWEST and $SDK_HIGHEST."
        return null
    }

    fun validateTargetSdk(value: Int?, minSdk: Int?): String? {
        if (value == null) return null
        if (value < MIN_SDK_LOWEST || value > SDK_HIGHEST) return "Target SDK must be between $MIN_SDK_LOWEST and $SDK_HIGHEST."
        if (minSdk != null && value < minSdk) return "Target SDK cannot be lower than the minimum SDK."
        return null
    }

    /** Every problem of [config], in plain words. Empty means the configuration can be used. */
    fun validate(config: BuildConfiguration): List<String> = listOfNotNull(
        validateApplicationId(config.applicationId),
        validateVersionName(config.versionName),
        validateVersionCode(config.versionCode),
        validateMinSdk(config.minSdk),
        validateTargetSdk(config.targetSdk, config.minSdk),
        if (config.options.shrinkResources == true && config.options.minify == false) {
            "Shrink resources needs code shrinking (minify) to be on."
        } else {
            null
        },
    )
}
