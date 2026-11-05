package com.almus.appcreatorpro.service.build

import android.content.Context
import com.almus.appcreatorpro.domain.github.CloudBuildConfig

/** The text of the workflow file the user adds to the repository once. */
fun interface WorkflowTemplateProvider {
    fun workflowText(): String
}

/** Reads the workflow file that is packaged with the app (Gradle copies .github/workflows into the assets). */
class AssetWorkflowTemplateProvider(private val context: Context) : WorkflowTemplateProvider {
    override fun workflowText(): String = try {
        context.assets.open(CloudBuildConfig.WORKFLOW_FILE).bufferedReader().use { it.readText() }
    } catch (e: java.io.IOException) {
        "The workflow file could not be read from the app. Copy ${CloudBuildConfig.WORKFLOW_PATH} from the project repository."
    }
}
