package com.almus.appcreatorpro.build

import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildConfigurationValidator
import com.almus.appcreatorpro.domain.build.BuildOptions
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.build.OutputFormat
import com.almus.appcreatorpro.domain.build.WorkflowInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuildConfigurationTest {
    @Test
    fun every_variant_uses_an_allowed_gradle_task() {
        for (v in BuildVariant.entries) {
            val c = BuildConfiguration(v, customOutput = OutputFormat.AAB, customRelease = true)
            assertTrue(c.gradleTask, c.gradleTask in BuildVariant.ALLOWED_TASKS)
        }
        assertEquals("assembleDebug", BuildConfiguration(BuildVariant.DEBUG_APK).gradleTask)
        assertEquals("assembleRelease", BuildConfiguration(BuildVariant.RELEASE_APK).gradleTask)
        assertEquals("bundleRelease", BuildConfiguration(BuildVariant.RELEASE_AAB).gradleTask)
        assertEquals("bundleDebug", BuildConfiguration(BuildVariant.CUSTOM, customOutput = OutputFormat.AAB).gradleTask)
    }

    @Test
    fun only_release_builds_need_the_release_key() {
        assertFalse(BuildConfiguration(BuildVariant.DEBUG_APK).needsReleaseSigning)
        assertTrue(BuildConfiguration(BuildVariant.RELEASE_APK).needsReleaseSigning)
        assertTrue(BuildConfiguration(BuildVariant.RELEASE_AAB).needsReleaseSigning)
        assertFalse(BuildConfiguration(BuildVariant.CUSTOM, customRelease = true, customSign = false).needsReleaseSigning)
        assertTrue(BuildConfiguration(BuildVariant.CUSTOM, customRelease = true, customSign = true).needsReleaseSigning)
    }

    @Test
    fun validators_accept_empty_values_and_reject_bad_ones() {
        assertNull(BuildConfigurationValidator.validateApplicationId(null))
        assertNull(BuildConfigurationValidator.validateApplicationId("com.example.app"))
        assertNotNull(BuildConfigurationValidator.validateApplicationId("example"))
        assertNotNull(BuildConfigurationValidator.validateApplicationId("1com.example"))
        assertNotNull(BuildConfigurationValidator.validateVersionCode(0))
        assertNull(BuildConfigurationValidator.validateVersionCode(7))
        assertNotNull(BuildConfigurationValidator.validateTargetSdk(25, 30))
        assertTrue(BuildConfigurationValidator.validate(BuildConfiguration()).isEmpty())
    }

    @Test
    fun workflow_inputs_are_exactly_the_inputs_the_workflow_declares() {
        val file = listOf("../.github/workflows/almus-cloud-build.yml", ".github/workflows/almus-cloud-build.yml").map(::File).firstOrNull { it.exists() } ?: return
        val text = file.readText()
        val inputsBlock = text.substringAfter("workflow_dispatch:").substringAfter("inputs:").substringBefore("\njobs:")
        val declared = Regex("(?m)^ {6}([a-z_]+):\\s*$").findAll(inputsBlock).map { it.groupValues[1] }.toSet()
        val sent = WorkflowInputs.build("dir", "abc123def456", "8.9", BuildConfiguration(BuildVariant.RELEASE_AAB, options = BuildOptions(true, true)), 2).keys
        assertEquals(declared, sent)
        assertEquals(setOf("project_dir", "build_id", "gradle_version"), WorkflowInputs.build("d", "i", "8.9", BuildConfiguration(), 1).keys)
    }
}
