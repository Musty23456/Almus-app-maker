package com.almus.appcreatorpro.service.build

import android.content.Context
import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import java.io.IOException

/** Supplies the text of the workflow file that the user adds to their own repository (once). */
fun interface WorkflowTemplateProvider {
    fun workflowText(): String
}

/**
 * Reads almus-cloud-build.yml from the app's assets. The Gradle build bundles the files of .github/workflows as
 * assets (see app/build.gradle.kts), so the workflow in the repository stays the single source of truth.
 */
class AssetWorkflowTemplateProvider(
    private val context: Context,
    private val assetName: String = CloudBuildConfig.WORKFLOW_FILE,
) : WorkflowTemplateProvider {

    override fun workflowText(): String = try {
        context.assets.open(assetName).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: IOException) {
        // Deliberately NOT valid workflow YAML, so nobody commits it by mistake.
        "# ERROR: the workflow template ($assetName) could not be read from the app. Reinstall the app and try again."
    }
}
