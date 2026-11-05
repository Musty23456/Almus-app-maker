package com.almus.appcreatorpro.github

import com.almus.appcreatorpro.domain.github.CloudBuildInputValidator
import com.almus.appcreatorpro.domain.github.CloudBuildValidator
import com.almus.appcreatorpro.domain.github.LogExcerpt
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.template.ProjectTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBuildValidatorTest {
    private fun kotlinFiles() = ProjectTemplates.filesFor("Demo App", "com.example.demo", ProjectType.ANDROID_KOTLIN).toMutableMap()

    @Test
    fun the_kotlin_and_java_starter_templates_pass_validation() {
        val kotlin = CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, kotlinFiles())
        assertTrue(kotlin.errors.toString(), kotlin.isValid)
        val java = CloudBuildValidator.validate(
            ProjectType.ANDROID_JAVA,
            ProjectTemplates.filesFor("Demo", "com.example.demo", ProjectType.ANDROID_JAVA),
        )
        assertTrue(java.errors.toString(), java.isValid)
    }

    @Test
    fun web_projects_are_rejected_with_a_clear_reason() {
        val report = CloudBuildValidator.validate(ProjectType.WEB, mapOf("index.html" to "<html/>"))
        assertFalse(report.isValid)
        assertTrue(report.errors.single().contains("cannot be compiled into an APK"))
    }

    @Test
    fun empty_project_is_invalid() {
        assertFalse(CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, emptyMap()).isValid)
    }

    @Test
    fun missing_settings_manifest_and_app_module_are_each_reported() {
        val files = kotlinFiles()
        files.remove("settings.gradle.kts")
        files.remove("app/src/main/AndroidManifest.xml")
        val errors = CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, files).errors.joinToString("\n")
        assertTrue(errors.contains("settings.gradle"))
        assertTrue(errors.contains("AndroidManifest.xml"))

        val noApp = kotlinFiles().apply { this["app/build.gradle.kts"] = "plugins { id(\"com.android.library\") }\nandroid { namespace = \"x\" }" }
        assertTrue(CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, noApp).errors.any { it.contains("No Android application module") })
    }

    @Test
    fun plugin_declared_with_apply_false_in_root_is_not_mistaken_for_the_app_module() {
        val files = kotlinFiles()
        files.remove("app/build.gradle.kts")
        assertTrue(CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, files).errors.any { it.contains("No Android application module") })
    }

    @Test
    fun secrets_in_files_block_the_upload() {
        val files = kotlinFiles()
        files["app/src/main/java/com/example/demo/Secrets.kt"] = "const val T = \"ghp_" + "a".repeat(36) + "\""
        val report = CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, files)
        assertFalse(report.isValid)
        assertTrue(report.errors.any { it.contains("Secrets.kt") && it.contains("GitHub token") })
        // The message must not echo the secret itself.
        assertFalse(report.errors.any { it.contains("ghp_aaaa") })

        val key = kotlinFiles().apply { this["key.txt"] = "-----BEGIN RSA PRIVATE KEY-----\nabc" }
        assertFalse(CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, key).isValid)
    }

    @Test
    fun plain_text_signing_password_is_only_a_warning() {
        val files = kotlinFiles().apply { this["app/signing.gradle.kts"] = "storePassword = \"hunter2\"" }
        val report = CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, files)
        assertTrue(report.isValid)
        assertTrue(report.warnings.any { it.contains("signing password") })
    }

    @Test
    fun size_and_file_count_limits() {
        val many = kotlinFiles()
        repeat(CloudBuildValidator.MAX_FILES) { many["extra/f$it.txt"] = "x" }
        assertTrue(CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, many).errors.any { it.contains("Too many files") })

        val big = kotlinFiles().apply { this["big.txt"] = "x".repeat((CloudBuildValidator.MAX_TOTAL_BYTES + 1).toInt()) }
        assertTrue(CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, big).errors.any { it.contains("too large") })
    }

    @Test
    fun detects_gradle_version_from_wrapper_properties() {
        val files = kotlinFiles().apply {
            this["gradle/wrapper/gradle-wrapper.properties"] = "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10.2-bin.zip"
        }
        val report = CloudBuildValidator.validate(ProjectType.ANDROID_KOTLIN, files)
        assertEquals("8.10.2", report.detectedGradleVersion)
        assertTrue(report.warnings.any { it.contains("wrapper") })
    }

    @Test
    fun directory_validation_blocks_traversal_and_odd_characters() {
        assertNull(CloudBuildInputValidator.validateDirectory("almus-projects/my-app"))
        listOf("", " ", "../x", "a/../b", "/abs", "a b", ".hidden/x", ".github/workflows", "a;rm -rf", "a/b/c/d/e", "x".repeat(101), "a//b", "trail/")
            .forEach { assertNotNull("should reject '$it'", CloudBuildInputValidator.validateDirectory(it)) }
    }

    @Test
    fun gradle_version_and_branch_validation() {
        assertNull(CloudBuildInputValidator.validateGradleVersion("8.9"))
        assertNull(CloudBuildInputValidator.validateGradleVersion("8.10.2"))
        listOf("", "8", "latest", "8.9; id", "8.9.1.2").forEach { assertNotNull(CloudBuildInputValidator.validateGradleVersion(it)) }
        assertNull(CloudBuildInputValidator.validateBranchName("feature/almus-build"))
        listOf("", "a..b", "x/", "-x", "a b", "a.lock", "a//b").forEach { assertNotNull("'$it'", CloudBuildInputValidator.validateBranchName(it)) }
    }
}

class LogExcerptTest {
    @Test
    fun prefers_error_lines_and_strips_timestamps_and_colors() {
        val esc = "\u001B"
        val log = listOf(
            "2026-10-30T08:00:00.1234567Z Starting a Gradle Daemon",
            "2026-10-30T08:00:01.0000000Z > Task :app:compileDebugKotlin FAILED",
            "2026-10-30T08:00:01.0000000Z e: file:///x/Main.kt:5:3 Unresolved reference: foo",
            "2026-10-30T08:00:02.0000000Z $esc[31mFAILURE: Build failed with an exception.$esc[0m",
            "2026-10-30T08:00:02.0000000Z * What went wrong:",
        ).joinToString("\n")
        val excerpt = LogExcerpt.extract(log)
        assertTrue(excerpt.contains("Unresolved reference: foo"))
        assertTrue(excerpt.contains("FAILURE: Build failed"))
        assertFalse(excerpt.contains("2026-10-30T"))
        assertFalse(excerpt.contains("\u001B"))
        assertFalse(excerpt.contains("Starting a Gradle Daemon"))
    }

    @Test
    fun falls_back_to_the_last_lines_and_redacts_token_like_text() {
        val log = (1..30).joinToString("\n") { "line $it" } + "\nBearer abcdefghijklmnopqrstuvwxyz0123456789\nghp_" + "b".repeat(30)
        val excerpt = LogExcerpt.extract(log)
        assertTrue(excerpt.contains("line 30"))
        assertFalse(excerpt.contains("line 1\n"))
        assertFalse(excerpt.contains("abcdefghijklmnopqrstuvwxyz"))
        assertFalse(excerpt.contains("ghp_b"))
    }

    @Test
    fun output_is_bounded_and_empty_input_gives_empty_output() {
        assertEquals("", LogExcerpt.extract(""))
        assertTrue(LogExcerpt.extract("error: " + "x".repeat(10_000)).length <= 2_000)
    }
}
