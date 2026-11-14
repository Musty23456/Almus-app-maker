package com.almus.appcreatorpro.domain.build

/** Builds the inputs sent when the cloud workflow is started. Every key here must be declared by the workflow file. */
object WorkflowInputs {
    /**
     * [workflowVersion] is the version of the workflow file found in the repository. A version-1 file knows only the
     * first three inputs, and GitHub rejects inputs a workflow does not declare, so only those are sent to it.
     */
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
        inputs["sign"] = (config.isRelease && config.needsReleaseSigning).toString()
        return inputs
    }
}
