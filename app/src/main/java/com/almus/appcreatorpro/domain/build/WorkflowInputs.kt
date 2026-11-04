package com.almus.appcreatorpro.domain.build

/** Builds the inputs that the app sends when it starts almus-cloud-build.yml. */
object WorkflowInputs {
    /** Workflow version 1 only knows these four inputs and only builds the debug APK. */
    private const val V1_TASK = "assembleDebug"

    /**
     * [workflowVersion] is the version found in the repository's workflow file. Version 1 gets exactly the four inputs
     * it declares (GitHub refuses unknown inputs); version 2 and later get all twelve. Empty text means "not set".
     */
    fun build(
        projectDir: String,
        buildId: String,
        gradleVersion: String,
        config: BuildConfiguration,
        workflowVersion: Int,
    ): Map<String, String> {
        if (workflowVersion < 2) {
            return linkedMapOf(
                "project_dir" to projectDir,
                "build_id" to buildId,
                "gradle_version" to gradleVersion,
                "gradle_task" to V1_TASK,
            )
        }
        return linkedMapOf(
            "project_dir" to projectDir,
            "build_id" to buildId,
            "gradle_version" to gradleVersion,
            "gradle_task" to config.gradleTask,
            "application_id" to config.applicationId.orEmpty(),
            "version_name" to config.versionName.orEmpty(),
            "version_code" to config.versionCode?.toString().orEmpty(),
            "min_sdk" to config.minSdk?.toString().orEmpty(),
            "target_sdk" to config.targetSdk?.toString().orEmpty(),
            "minify" to config.options.minify?.toString().orEmpty(),
            "shrink_resources" to config.options.shrinkResources?.toString().orEmpty(),
            "sign" to config.needsReleaseSigning.toString(),
        )
    }
}
