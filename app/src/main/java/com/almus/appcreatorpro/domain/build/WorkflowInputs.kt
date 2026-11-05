package com.almus.appcreatorpro.domain.build

/**
 * The inputs sent to the cloud workflow. Every key is declared by almus-cloud-build.yml (RepositoryGuardsTest checks that
 * the two lists are identical). Workflow version 1 only knows the first three; version 2 knows all of them.
 */
object WorkflowInputs {
    fun build(
        projectDir: String,
        buildId: String,
        gradleVersion: String,
        config: BuildConfiguration,
        workflowVersion: Int,
    ): Map<String, String> {
        val inputs = linkedMapOf(
            "project_dir" to projectDir,
            "build_id" to buildId,
            "gradle_version" to gradleVersion,
        )
        if (workflowVersion < 2) return inputs
        inputs["gradle_task"] = config.gradleTask
        inputs["application_id"] = config.applicationId.orEmpty()
        inputs["version_name"] = config.versionName.orEmpty()
        inputs["version_code"] = config.versionCode?.toString().orEmpty()
        inputs["min_sdk"] = config.minSdk?.toString().orEmpty()
        inputs["target_sdk"] = config.targetSdk?.toString().orEmpty()
        inputs["minify"] = config.options.minify?.toString().orEmpty()
        inputs["shrink_resources"] = config.options.shrinkResources?.toString().orEmpty()
        inputs["sign"] = if (config.needsReleaseSigning) "true" else "false"
        return inputs
    }
}
