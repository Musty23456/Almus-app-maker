package com.almus.appcreatorpro.github

import com.almus.appcreatorpro.domain.github.CloudBuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Guards that read files from the repository checkout (Gradle runs unit tests from the app/ folder).
 * They are skipped, not failed, if the files are not where expected.
 */
class RepositoryGuardsTest {
    private fun find(vararg candidates: String): File? = candidates.map(::File).firstOrNull { it.exists() }

    private val workflow get() = find("../.github/workflows/almus-cloud-build.yml", ".github/workflows/almus-cloud-build.yml")
    private val buildWorkflow get() = find("../.github/workflows/android-build.yml", ".github/workflows/android-build.yml")

    @Test
    fun cloud_build_workflow_carries_the_marker_the_app_looks_for() {
        val file = workflow
        assumeTrue(file != null)
        assertEquals(CloudBuildConfig.WORKFLOW_MARKER, file!!.readLines().first())
        assertTrue(CloudBuildConfig.WORKFLOW_PATH.endsWith(file.name))
    }

    @Test
    fun cloud_build_workflow_is_least_privilege_and_secret_free() {
        val text = (workflow ?: return).readText()
        assertTrue(text.contains("permissions:\n  contents: read"))
        assertFalse("must not use secrets", Regex("\\$\\{\\{\\s*secrets\\.").containsMatchIn(text))
        assertFalse(text.contains("pull_request_target"))
        assertFalse("no write permissions", Regex("^\\s+[a-z-]+:\\s*write\\s*$", RegexOption.MULTILINE).containsMatchIn(text))
        assertTrue(text.contains("persist-credentials: false"))
        assertTrue(text.contains("cache-disabled: true"))
        assertTrue("manual trigger only", text.contains("workflow_dispatch:") && !text.contains("\n  push:") && !text.contains("\n  pull_request:"))
    }

    @Test
    fun workflow_inputs_never_reach_shell_commands_directly() {
        val lines = (workflow ?: return).readLines()
        var inRun = false
        var runIndent = 0
        val offenders = mutableListOf<String>()
        for (line in lines) {
            val indent = line.length - line.trimStart().length
            if (inRun && line.isNotBlank() && indent <= runIndent) inRun = false
            if (!inRun && line.trimStart().startsWith("run:")) {
                inRun = true
                runIndent = indent
                if (line.contains("\${{")) offenders += line
                continue
            }
            if (inRun && line.contains("\${{")) offenders += line
        }
        assertTrue("expressions inside run: blocks allow shell injection: $offenders", offenders.isEmpty())
    }

    @Test
    fun the_existing_build_workflow_does_not_persist_credentials() {
        val text = (buildWorkflow ?: return).readText()
        assertTrue(text.contains("persist-credentials: false"))
        assertTrue(text.contains("permissions:\n  contents: read"))
    }

    @Test
    fun app_source_contains_no_tokens_or_client_secrets() {
        val root = find("src/main/java") ?: return
        val badPatterns = listOf(
            Regex("gh[pousr]_[A-Za-z0-9]{30,}"),
            Regex("github_pat_[A-Za-z0-9_]{20,}"),
            Regex("client_secret\\s*[=:]\\s*\"[^\"]+\"", RegexOption.IGNORE_CASE),
        )
        val offenders = root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { f -> badPatterns.any { it.containsMatchIn(f.readText()) } }
            .map { it.name }.toList()
        assertTrue("possible secret in: $offenders", offenders.isEmpty())
    }

    @Test
    fun the_app_never_logs() {
        val root = find("src/main/java") ?: return
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> Regex("\\bLog\\.[a-z]\\(|println\\(|Timber\\.|HttpLoggingInterceptor").containsMatchIn(f.readText()) }
            .map { it.name }.toList()
        assertTrue("logging could leak tokens: $offenders", offenders.isEmpty())
    }

    @Test
    fun the_existing_workflow_still_builds_and_uploads_the_debug_apk() {
        val text = (buildWorkflow ?: return).readText()
        assertTrue(text.contains("gradle assembleDebug"))
        assertTrue(text.contains("gradle testDebugUnitTest"))
        assertTrue(text.contains("app/build/outputs/apk/debug/app-debug.apk"))
        assertTrue(text.contains("actions/upload-artifact@v4"))
        assertTrue("an upload without an APK must fail the build", text.contains("if-no-files-found: error"))
    }

    @Test
    fun the_cloud_workflow_uploads_the_apk_under_the_name_the_app_looks_for() {
        val text = (workflow ?: return).readText()
        assertTrue(text.contains("name: " + com.almus.appcreatorpro.domain.model.BuildArtifactInfo.APK_PREFIX + "\${{ inputs.build_id }}"))
        assertTrue(text.contains("*.apk"))
    }

    @Test
    fun the_file_provider_exposes_only_the_apk_folder() {
        val paths = find("src/main/res/xml/file_paths.xml", "app/src/main/res/xml/file_paths.xml") ?: return
        val xml = paths.readText()
        assertTrue(xml.contains("path=\"apks/\""))
        assertFalse("must not expose the whole files folder", Regex("path=\"(\\.|/)?\"").containsMatchIn(xml))
        assertFalse(xml.contains("root-path") || xml.contains("external-path") || xml.contains("cache-path"))
        val manifest = (find("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml") ?: return).readText()
        assertTrue(manifest.contains("androidx.core.content.FileProvider"))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertTrue(manifest.contains("@xml/file_paths"))
        assertFalse("the app must not ask to install packages", manifest.contains("REQUEST_INSTALL_PACKAGES"))
        assertFalse("downloads use private storage; no storage permission", manifest.contains("WRITE_EXTERNAL_STORAGE"))
    }

    private fun runBlockLinesWith(text: String, needle: String): List<String> {
        val hits = mutableListOf<String>()
        var inRun = false
        var runIndent = 0
        for (line in text.lines()) {
            val indent = line.length - line.trimStart().length
            if (inRun && line.isNotBlank() && indent <= runIndent) inRun = false
            if (!inRun && line.trimStart().startsWith("run:")) {
                inRun = true
                runIndent = indent
            }
            if (inRun && line.contains(needle)) hits += line.trim()
        }
        return hits
    }

    @Test
    fun signing_secrets_are_used_only_through_environment_variables() {
        val build = (buildWorkflow ?: return).readText()
        val create = (find("../.github/workflows/create-signing-key.yml", ".github/workflows/create-signing-key.yml") ?: return).readText()
        for (text in listOf(build, create)) {
            assertTrue("secrets must not appear inside run: scripts", runBlockLinesWith(text, "secrets.").isEmpty())
            val names = Regex("secrets\\.([A-Za-z0-9_]+)").findAll(text).map { it.groupValues[1] }.toSet()
            assertTrue("only the Almus signing secrets may be used: $names", names.all { it.startsWith("ALMUS_KEYSTORE_") })
        }
        assertTrue(build.contains("persist-credentials: false"))
        assertTrue(build.contains("permissions:\n  contents: read"))
        assertFalse("never run on pull requests with secrets", build.contains("pull_request"))
        assertTrue("the key must be removed from the runner", build.contains("rm -f \"\$RUNNER_TEMP/almus.jks\""))
        assertTrue("signing must be verified after the build", build.contains("apksigner") && build.contains("Android Debug"))
    }

    @Test
    fun key_creation_refuses_to_replace_an_existing_key_and_expires_the_download() {
        val create = (find("../.github/workflows/create-signing-key.yml", ".github/workflows/create-signing-key.yml") ?: return).readText()
        assertTrue(create.contains("ALMUS_KEYSTORE_BASE64 already exists"))
        assertTrue(create.contains("retention-days: 1"))
        assertTrue("manual only", create.contains("workflow_dispatch:") && !create.contains("\n  push:"))
        assertFalse("the password must never be echoed", Regex("echo[^\\n]*KEYSTORE_PASSWORD").containsMatchIn(create))
    }

    @Test
    fun the_gradle_file_reads_signing_data_from_the_environment_and_has_no_secret_literals() {
        val gradle = (find("build.gradle.kts", "app/build.gradle.kts") ?: return).readText()
        assertTrue(gradle.contains("System.getenv(\"ALMUS_KEYSTORE_PATH\")"))
        assertTrue(gradle.contains("System.getenv(\"ALMUS_KEYSTORE_PASSWORD\")"))
        assertFalse(Regex("(storePassword|keyPassword)\\s*=\\s*\"").containsMatchIn(gradle))
        val ignore = (find("../.gitignore", ".gitignore") ?: return).readText()
        assertTrue(ignore.contains("*.jks") && ignore.contains("*.keystore"))
    }
}
