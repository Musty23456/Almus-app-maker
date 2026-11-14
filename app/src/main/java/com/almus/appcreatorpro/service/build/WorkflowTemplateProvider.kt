package com.almus.appcreatorpro.service.build

import android.content.Context

/** Gives the text of the workflow file the user adds to the repository once. */
fun interface WorkflowTemplateProvider {
    fun workflowText(): String
}

/** Reads almus-cloud-build.yml from the app's assets (the Gradle build copies .github/workflows there). */
class AssetWorkflowTemplateProvider(private val context: Context) : WorkflowTemplateProvider {
    override fun workflowText(): String = try {
        context.assets.open(FILE).bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        "# The workflow file could not be read from the app.\n# Copy .github/workflows/$FILE from the project source instead."
    }

    private companion object {
        const val FILE = "almus-cloud-build.yml"
    }
}
