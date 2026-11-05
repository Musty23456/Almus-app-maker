package com.almus.appcreatorpro.build

import com.almus.appcreatorpro.domain.build.ArtifactKind
import com.almus.appcreatorpro.domain.build.ArtifactNaming
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildConfigurationValidator
import com.almus.appcreatorpro.domain.build.BuildOptions
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.build.OutputFormat
import com.almus.appcreatorpro.domain.build.SigningSecretNames
import com.almus.appcreatorpro.domain.build.SigningSecrets
import com.almus.appcreatorpro.domain.build.WorkflowInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigurationTest {
    @Test
    fun each_variant_maps_to_one_allowed_gradle_task() {
        assertEquals("assembleDebug", BuildConfiguration(BuildVariant.DEBUG_APK).gradleTask)
        assertEquals("assembleRelease", BuildConfiguration(BuildVariant.RELEASE_APK).gradleTask)
        assertEquals("bundleRelease", BuildConfiguration(BuildVariant.RELEASE_AAB).gradleTask)
        val custom = BuildConfiguration(BuildVariant.CUSTOM, customOutput = OutputFormat.AAB, customRelease = false)
        assertEquals("bundleDebug", custom.gradleTask)
        for (v in BuildVariant.entries) assertTrue(BuildVariant.ALLOWED_TASKS.contains(BuildConfiguration(v).gradleTask))
    }

    @Test
    fun only_release_builds_that_sign_need_the_release_key() {
        assertFalse(BuildConfiguration.DEFAULT.needsReleaseSigning)
        assertTrue(BuildConfiguration(BuildVariant.RELEASE_APK).needsReleaseSigning)
        assertTrue(BuildConfiguration(BuildVariant.RELEASE_AAB).needsReleaseSigning)
        assertFalse(BuildConfiguration(BuildVariant.CUSTOM, customRelease = true, customSign = false).needsReleaseSigning)
        assertTrue(BuildConfiguration(BuildVariant.RELEASE_AAB).isBundle)
    }

    @Test
    fun short_labels_are_the_names_users_see() {
        assertEquals("Debug APK", BuildConfiguration.DEFAULT.shortLabel())
        assertEquals("Release AAB", BuildConfiguration(BuildVariant.RELEASE_AAB).shortLabel())
    }

    @Test
    fun overrides_are_validated() {
        assertNull(BuildConfigurationValidator.validateApplicationId(null))
        assertNull(BuildConfigurationValidator.validateApplicationId("com.example.app"))
        assertNotNull(BuildConfigurationValidator.validateApplicationId("example"))
        assertNotNull(BuildConfigurationValidator.validateApplicationId("1com.example"))
        assertNotNull(BuildConfigurationValidator.validateVersionCode(0))
        assertNull(BuildConfigurationValidator.validateVersionCode(12))
        assertNotNull(BuildConfigurationValidator.validateTargetSdk(24, 26))
        assertTrue(BuildConfigurationValidator.validate(BuildConfiguration.DEFAULT).isEmpty())
        val bad = BuildConfiguration(options = BuildOptions(minify = false, shrinkResources = true))
        assertEquals(1, BuildConfigurationValidator.validate(bad).size)
    }

    @Test
    fun workflow_inputs_version_1_sends_three_values_and_version_2_all_of_them() {
        val c = BuildConfiguration(BuildVariant.RELEASE_AAB, versionCode = 7)
        assertEquals(setOf("project_dir", "build_id", "gradle_version"), WorkflowInputs.build("d", "id", "8.9", c, 1).keys)
        val v2 = WorkflowInputs.build("d", "id", "8.9", c, 2)
        assertEquals("bundleRelease", v2["gradle_task"])
        assertEquals("7", v2["version_code"])
        assertEquals("true", v2["sign"])
        assertEquals("", v2["application_id"])
    }

    @Test
    fun artifact_names_are_told_apart() {
        assertEquals(ArtifactKind.APK, ArtifactNaming.kindOf("almus-apk-abc"))
        assertEquals(ArtifactKind.AAB, ArtifactNaming.kindOf("almus-aab-abc"))
        assertEquals(ArtifactKind.INTERMEDIATE, ArtifactNaming.kindOf("almus-unsigned-abc"))
        assertEquals(ArtifactKind.LOG, ArtifactNaming.kindOf("almus-build-log-abc"))
        assertEquals(ArtifactKind.OTHER, ArtifactNaming.kindOf("something"))
    }

    @Test
    fun signing_secrets_never_show_their_values() {
        val s = SigningSecrets("KEYSTORE-DATA", "store-pass-1", "alias", "key-pass-1")
        assertFalse(s.toString().contains("store-pass-1"))
        assertFalse(s.toString().contains("KEYSTORE-DATA"))
        assertEquals(SigningSecretNames.ALL, s.entries().map { it.first })
    }
}
