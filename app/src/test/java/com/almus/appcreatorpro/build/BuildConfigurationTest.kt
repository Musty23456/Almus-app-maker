package com.almus.appcreatorpro.build

import com.almus.appcreatorpro.data.repository.BuildConfigurationJson
import com.almus.appcreatorpro.domain.build.ArtifactKind
import com.almus.appcreatorpro.domain.build.ArtifactNaming
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

class BuildConfigurationTest {

    @Test
    fun the_default_is_the_plain_debug_apk_of_the_earlier_phases() {
        val c = BuildConfiguration.DEFAULT
        assertEquals("assembleDebug", c.gradleTask)
        assertFalse(c.needsReleaseSigning)
        assertFalse(c.hasOverrides)
        assertFalse(c.needsAdvancedWorkflow)
        assertEquals("Debug APK", c.shortLabel())
    }

    @Test
    fun release_builds_are_signed_and_use_their_own_tasks() {
        val apk = BuildConfiguration(BuildVariant.RELEASE_APK)
        val aab = BuildConfiguration(BuildVariant.RELEASE_AAB)
        assertEquals("assembleRelease", apk.gradleTask)
        assertEquals("bundleRelease", aab.gradleTask)
        assertTrue(apk.needsReleaseSigning && aab.needsReleaseSigning)
        assertTrue(aab.isBundle)
        assertEquals("Release AAB", aab.shortLabel())
        assertTrue(apk.needsAdvancedWorkflow)
    }

    @Test
    fun a_custom_build_follows_its_own_choices() {
        val c = BuildConfiguration(BuildVariant.CUSTOM, customOutput = OutputFormat.AAB, customRelease = true, customSign = false)
        assertEquals("bundleRelease", c.gradleTask)
        assertFalse(c.needsReleaseSigning)
        val debugAab = BuildConfiguration(BuildVariant.CUSTOM, customOutput = OutputFormat.AAB)
        assertEquals("bundleDebug", debugAab.gradleTask)
        assertFalse(debugAab.needsReleaseSigning)
    }

    @Test
    fun every_configuration_uses_a_task_from_the_allow_list() {
        for (v in BuildVariant.entries) for (o in OutputFormat.entries) for (r in listOf(true, false)) {
            val task = BuildConfiguration(v, customOutput = o, customRelease = r).gradleTask
            assertTrue(task, task in BuildVariant.ALLOWED_TASKS)
        }
    }

    @Test
    fun the_stored_form_round_trips() {
        val c = BuildConfiguration(
            BuildVariant.RELEASE_AAB, applicationId = "com.shop.app", versionName = "1.2", versionCode = 12,
            minSdk = 26, targetSdk = 35, options = BuildOptions(minify = true, shrinkResources = false), signingProfileId = "abc123abc123",
        )
        assertEquals(c, BuildConfigurationJson.fromJson(BuildConfigurationJson.toJson(c)))
        assertNull(BuildConfigurationJson.fromJson("not json"))
    }

    @Test
    fun validator_rules() {
        assertNull(BuildConfigurationValidator.validateApplicationId(null))
        assertNull(BuildConfigurationValidator.validateApplicationId("com.example.app"))
        assertNotNull(BuildConfigurationValidator.validateApplicationId("single"))
        assertNotNull(BuildConfigurationValidator.validateApplicationId("com.1bad.app"))
        assertNull(BuildConfigurationValidator.validateVersionName("1.0.0-beta+3"))
        assertNotNull(BuildConfigurationValidator.validateVersionName("bad name"))
        assertNull(BuildConfigurationValidator.validateVersionCode(7))
        assertNotNull(BuildConfigurationValidator.validateVersionCode(0))
        assertNotNull(BuildConfigurationValidator.validateMinSdk(5))
        assertNotNull(BuildConfigurationValidator.validateTargetSdk(26, 30))
        assertNull(BuildConfigurationValidator.validateTargetSdk(35, 26))
        assertTrue(BuildConfigurationValidator.validate(BuildConfiguration.DEFAULT).isEmpty())
        assertEquals(1, BuildConfigurationValidator.validate(BuildConfiguration(options = BuildOptions(minify = false, shrinkResources = true))).size)
    }

    @Test
    fun workflow_inputs_match_the_workflow_version() {
        val v1 = WorkflowInputs.build("dir", "abc123def456", "8.9", BuildConfiguration(BuildVariant.RELEASE_APK), 1)
        assertEquals(listOf("project_dir", "build_id", "gradle_version", "gradle_task"), v1.keys.toList())
        assertEquals("assembleDebug", v1["gradle_task"])
        val v2 = WorkflowInputs.build("dir", "abc123def456", "8.9", BuildConfiguration(BuildVariant.RELEASE_AAB, versionCode = 9), 2)
        assertEquals(12, v2.size)
        assertEquals("bundleRelease", v2["gradle_task"])
        assertEquals("true", v2["sign"])
        assertEquals("9", v2["version_code"])
        assertEquals("", v2["application_id"])
    }

    @Test
    fun artifact_names_are_told_apart() {
        assertEquals(ArtifactKind.APK, ArtifactNaming.kindOf("almus-apk-abc"))
        assertEquals(ArtifactKind.AAB, ArtifactNaming.kindOf("almus-aab-abc"))
        assertEquals(ArtifactKind.BUILD_LOG, ArtifactNaming.kindOf("almus-build-log-abc"))
        assertEquals(ArtifactKind.INTERMEDIATE, ArtifactNaming.kindOf("almus-unsigned-abc"))
        assertEquals(ArtifactKind.OTHER, ArtifactNaming.kindOf("something"))
    }
}
