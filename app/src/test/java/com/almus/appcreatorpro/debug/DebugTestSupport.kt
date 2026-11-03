package com.almus.appcreatorpro.debug

import java.io.File

/** Loads the sample projects and logs under src/test/resources/phase8-samples. */
object Samples {
    private fun root(): File {
        val url = Samples::class.java.getResource("/phase8-samples") ?: error("phase8-samples resources not found")
        return File(url.toURI())
    }

    fun text(relativePath: String): String = File(root(), relativePath).readText()

    /** All files of a sample project (relative path -> text), without the *.log files that sit next to it. */
    fun project(name: String): Map<String, String> {
        val dir = File(root(), name)
        return dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".log") }
            .associate { it.relativeTo(dir).path.replace('\\', '/') to it.readText() }
    }
}

fun BuildErrorAnalyzer.analyzeSample(path: String, projectFiles: Collection<String> = emptyList()): AnalysisResult =
    BuildErrorAnalyzer(ProjectPathResolver(projectFiles)).analyze(Samples.text(path))

fun List<BuildIssue>.byRule(rule: String): List<BuildIssue> = filter { it.rule == rule }
