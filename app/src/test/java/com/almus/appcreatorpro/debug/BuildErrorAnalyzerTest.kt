package com.almus.appcreatorpro.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildErrorAnalyzerTest {

    private val kotlinFiles = Samples.project("kotlin-errors").keys
    private val javaFiles = Samples.project("java-errors").keys

    // ---------------------------------------------------------------- real compiler output (kotlinc 2.0.21, javac 21)

    @Test fun realKotlincLogIsParsedWithFileLineColumnAndCategory() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(kotlinFiles)).analyze(Samples.text("kotlin-errors/build.log"))
        assertEquals(6, r.errorCount)
        assertTrue(r.issues.all { it.category == ErrorCategory.KOTLIN_COMPILATION })
        val unresolved = r.issues.first { it.symbol == "labl" }
        assertEquals("kotlin.unresolved-reference", unresolved.rule)
        assertEquals(10, unresolved.line)
        assertEquals(16, unresolved.column)
        assertEquals("app/src/main/java/com/example/broken/Calculator.kt", unresolved.projectPath)
        assertTrue(unresolved.canNavigate)
        assertEquals("Calculator.kt:10:16", unresolved.locationLabel)
        assertNotNull(unresolved.suggestion)
        assertTrue(unresolved.context.any { it.contains("return labl + value") })
    }

    @Test fun realKotlincLogRecognisesCommonRules() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(kotlinFiles)).analyze(Samples.text("kotlin-errors/build.log"))
        val rules = r.issues.map { it.rule }.toSet()
        assertTrue(rules.containsAll(setOf(
            "kotlin.type-mismatch", "kotlin.unresolved-reference", "kotlin.val-reassign",
            "kotlin.nullable-receiver", "kotlin.missing-argument",
        )))
        val util = r.issues.first { it.symbol == "undefinedHelper" }
        assertEquals("app/src/main/java/com/example/broken/Util.kt", util.projectPath)
        assertEquals(5, util.line)
    }

    @Test fun realJavacLogGivesSymbolAndCaretColumn() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(javaFiles)).analyze(Samples.text("java-errors/build.log"))
        assertEquals(4, r.errorCount)
        assertTrue(r.issues.all { it.category == ErrorCategory.JAVA_COMPILATION })
        val nam = r.issues.first { it.symbol == "nam" }
        assertEquals("java.cannot-find-symbol", nam.rule)
        assertEquals(7, nam.line)
        assertEquals("app/src/main/java/com/example/broken/Greeter.java", nam.projectPath)
        assertTrue(nam.column != null && nam.column!! > 1)
        assertEquals("cannot find symbol: nam", nam.message)
        val types = r.issues.first { it.rule == "java.incompatible-types" }
        assertEquals(12, types.line)
        assertTrue(r.issues.any { it.symbol == "ArrayList" })
    }

    @Test fun realJavacSyntaxError() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(javaFiles)).analyze(Samples.text("java-errors/syntax-build.log"))
        assertEquals(1, r.errorCount)
        assertEquals("java.syntax", r.issues[0].rule)
        assertEquals(5, r.issues[0].line)
    }

    // ---------------------------------------------------------------- Gradle formatted logs

    @Test fun gradleFormattedKotlinLogDropsSummariesAndKeepsTasks() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(kotlinFiles)).analyze(Samples.text("kotlin-errors/gradle-build.log"))
        assertEquals(true, r.buildFailed)
        assertEquals(3, r.errorCount)
        assertEquals(1, r.warningCount)
        // "Compilation error. See log for more details", "Execution failed for task" and the exit code only repeat the above.
        assertTrue(r.issues.none { it.rule.startsWith("summary.") })
        assertEquals(":app:compileDebugKotlin", r.issues.first().task)
        val warning = r.issues.first { it.severity == Severity.WARNING }
        assertEquals("kotlin.unused", warning.rule)
        assertEquals("unused", warning.symbol)
        assertEquals(4, warning.line)
    }

    @Test fun errorsComeBeforeWarnings() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(kotlinFiles)).analyze(Samples.text("kotlin-errors/gradle-build.log"))
        val severities = r.issues.map { it.severity.rank }
        assertEquals(severities.sorted(), severities)
    }

    @Test fun missingDependencyIsFoundWithCoordinate() {
        val r = BuildErrorAnalyzer().analyze(Samples.text("gradle-errors/dependency.log"))
        val deps = r.issues.byRule("dependency.not-found")
        assertEquals(2, deps.size)
        assertEquals(ErrorCategory.MISSING_DEPENDENCY, deps[0].category)
        assertEquals("com.squareup.okhttp3:okhtttp:4.12.0", deps[0].symbol)
        assertTrue(deps[0].suggestion!!.contains("okhtttp"))
        assertTrue(deps.any { it.symbol == "androidx.core:core-ktx:99.0.0" && it.suggestion!!.contains("google()") })
        assertTrue(r.issues.none { it.rule == "summary.task-failed" })
    }

    @Test fun sdkLocationAndPlatformErrorsAreSdkConfiguration() {
        val sdk = BuildErrorAnalyzer().analyze(Samples.text("gradle-errors/sdk.log"))
        assertEquals("sdk.location", sdk.issues[0].rule)
        assertEquals(ErrorCategory.SDK_CONFIGURATION, sdk.issues[0].category)
        assertTrue(sdk.issues[0].suggestion!!.contains("local.properties"))

        val platform = BuildErrorAnalyzer().analyze(Samples.text("gradle-errors/sdk-licence.log"))
        val p = platform.issues.first { it.rule == "sdk.platform-missing" }
        assertEquals("36", p.symbol)
        assertEquals(ErrorCategory.SDK_CONFIGURATION, p.category)
    }

    @Test fun minSdkConflictFromLibraryManifest() {
        val r = BuildErrorAnalyzer().analyze(Samples.text("gradle-errors/min-sdk.log"))
        val issue = r.issues.first { it.rule == "sdk.min-sdk-lib" }
        assertEquals(ErrorCategory.SDK_CONFIGURATION, issue.category)
        assertEquals("androidx.camera:camera-core:1.4.0", issue.symbol)
        assertTrue(issue.suggestion!!.contains("23"))
    }

    @Test fun gradleKotlinScriptErrorKeepsWhereLocation() {
        val files = listOf("app/build.gradle.kts", "settings.gradle.kts")
        val r = BuildErrorAnalyzer(ProjectPathResolver(files)).analyze(Samples.text("gradle-errors/build-script.log"))
        val issue = r.issues.first()
        assertEquals(ErrorCategory.GRADLE, issue.category)
        assertEquals(22, issue.line)
        assertEquals("app/build.gradle.kts", issue.projectPath)
        assertTrue(issue.message.contains("Unresolved reference"))
        assertEquals("implementaion", issue.symbol)
    }

    @Test fun manifestExportedAndResourceLinkingErrors() {
        val files = listOf(
            "app/src/main/AndroidManifest.xml", "app/src/main/res/layout/activity_main.xml", "app/src/main/res/values/themes.xml",
        )
        val r = BuildErrorAnalyzer(ProjectPathResolver(files)).analyze(Samples.text("resource-errors/aapt.log"))
        val manifest = r.issues.first { it.category == ErrorCategory.MANIFEST }
        assertEquals("manifest.exported", manifest.rule)
        assertEquals(12, manifest.line)
        assertEquals("app/src/main/AndroidManifest.xml", manifest.projectPath)

        val resources = r.issues.filter { it.category == ErrorCategory.RESOURCE_LINKING }
        assertEquals(3, resources.size)
        val str = resources.first { it.symbol == "string/missing_title" }
        assertEquals(9, str.line)
        assertEquals("app/src/main/res/layout/activity_main.xml", str.projectPath)
        assertTrue(resources.any { it.symbol == "drawable/ic_missing" && it.line == 14 })
        assertTrue(resources.any { it.rule == "resource.attribute-not-found" && it.line == 3 })
        assertTrue(r.issues.none { it.rule.startsWith("summary.") })
    }

    @Test fun manifestMergerConflictPointsAtAttribute() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(listOf("app/src/main/AndroidManifest.xml"))).analyze(Samples.text("resource-errors/merger.log"))
        val issue = r.issues.first { it.rule == "manifest.merge-conflict" }
        assertEquals(ErrorCategory.MANIFEST, issue.category)
        assertEquals(7, issue.line)
        assertEquals("app/src/main/AndroidManifest.xml", issue.projectPath)
        assertTrue(issue.suggestion!!.contains("tools:replace=\"android:allowBackup\""))
    }

    @Test fun xmlParseErrorInResourceFile() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(listOf("app/src/main/res/values/strings.xml"))).analyze(Samples.text("resource-errors/xml-parse.log"))
        val issue = r.issues.first { it.category == ErrorCategory.RESOURCE_LINKING }
        assertEquals(5, issue.line)
        assertEquals(9, issue.column)
        assertEquals("app/src/main/res/values/strings.xml", issue.projectPath)
        assertTrue(issue.message.contains("must be terminated"))
    }

    // ---------------------------------------------------------------- behaviour

    @Test fun unknownGradleFailureStillProducesOneIssue() {
        val log = """
            FAILURE: Build failed with an exception.

            * What went wrong:
            Something exotic happened in the build.
            > The exotic thing is not understood by any rule.

            BUILD FAILED in 1s
        """.trimIndent()
        val r = BuildErrorAnalyzer().analyze(log)
        assertEquals(1, r.errorCount)
        assertEquals("gradle.unrecognised", r.issues[0].rule)
        assertTrue(r.issues[0].message.contains("exotic"))
    }

    @Test fun failedBuildWithNoRecognisableErrorSaysSo() {
        val r = BuildErrorAnalyzer().analyze("some output\nBUILD FAILED in 1s\n")
        assertEquals(1, r.errorCount)
        assertTrue(r.issues[0].message.contains("no specific error"))
    }

    @Test fun successfulBuildHasNoIssues() {
        val r = BuildErrorAnalyzer().analyze("> Task :app:assembleDebug\nBUILD SUCCESSFUL in 1m 2s\n")
        assertEquals(false, r.buildFailed)
        assertTrue(r.issues.isEmpty())
    }

    @Test fun emptyLogIsHandled() {
        assertEquals(AnalysisResult.EMPTY.issues, BuildErrorAnalyzer().analyze("").issues)
        assertNull(BuildErrorAnalyzer().analyze("").buildFailed)
    }

    @Test fun duplicateMessagesAreReportedOnce() {
        val line = "e: file:///x/A.kt:3:4 Unresolved reference 'foo'.\n"
        val r = BuildErrorAnalyzer().analyze(line + line + line)
        assertEquals(1, r.issues.size)
    }

    @Test fun pathThatIsNotInTheProjectCannotNavigate() {
        val r = BuildErrorAnalyzer(ProjectPathResolver(listOf("other/Thing.kt"))).analyze("e: file:///x/y/Missing.kt:3:4 Unresolved reference 'foo'.")
        assertFalse(r.issues[0].canNavigate)
        assertEquals("Missing.kt:3:4", r.issues[0].locationLabel)
    }

    @Test fun secretsInLogsAreRedactedInMessagesAndContext() {
        val log = "e: file:///x/A.kt:3:4 Unresolved reference 'x'.\nERROR: could not call with GITHUB_TOKEN=ghp_abcdefghijklmnopqrstuvwxyz0123456789 now\n"
        val r = BuildErrorAnalyzer().analyze(log)
        assertTrue(r.issues.none { (it.message + it.context.joinToString()).contains("ghp_") })
    }

    @Test fun fileNamesWithSecretWordsKeepTheirLineNumbers() {
        val log = "e: file:///x/PasswordValidator.kt:12:5 Unresolved reference 'foo'.\n"
        val r = BuildErrorAnalyzer().analyze(log)
        assertEquals(12, r.issues[0].line)
        assertEquals(5, r.issues[0].column)
        assertTrue(LogCleaner.redact("PasswordValidator.kt:12:5 failed").contains("PasswordValidator.kt:12:5"))
    }

    @Test fun errorCapKeepsFirstErrorsAndCountsOmitted() {
        val log = (1..40).joinToString("\n") { "e: file:///x/A.kt:$it:1 Unresolved reference 'n$it'." }
        val r = BuildErrorAnalyzer(maxErrors = 10).analyze(log)
        assertEquals(10, r.errorCount)
        assertEquals(30, r.omittedWarnings)
    }

    @Test fun logIndexPointsAtTheLogLine() {
        val log = "line one\nline two\ne: file:///x/A.kt:3:4 Unresolved reference 'foo'.\n"
        val r = BuildErrorAnalyzer().analyze(log)
        assertEquals(2, r.issues[0].logIndex)
        val entries = LogParser.parse(log)
        assertTrue(entries[2].text.startsWith("e: "))
    }
}
