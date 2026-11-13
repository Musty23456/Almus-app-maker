package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.service.artifacts.ApkPackageReader
import com.almus.appcreatorpro.service.artifacts.ApkPackageResult
import com.almus.appcreatorpro.service.artifacts.ArtifactAnalyzer
import com.almus.appcreatorpro.service.artifacts.ArtifactAssessor
import com.almus.appcreatorpro.service.artifacts.ArtifactEntry
import com.almus.appcreatorpro.service.artifacts.ArtifactRegistry
import com.almus.appcreatorpro.service.artifacts.BuildContext
import com.almus.appcreatorpro.service.artifacts.BuildIntent
import com.almus.appcreatorpro.service.artifacts.BuildKind
import com.almus.appcreatorpro.service.artifacts.StoredApk
import com.almus.appcreatorpro.ui.artifacts.ArtifactTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Phase33TextsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun entry(name: String, kind: BuildKind, bytes: ByteArray = plainApk()): ArtifactEntry {
        val f = File(tmp.root, name).apply { writeBytes(bytes) }
        val ctx = BuildContext("Shop", BuildIntent("x", kind, null, null, null, null))
        return ArtifactEntry("b1", StoredApk("b1", f), "Shop", ctx, f.length(), 1L)
    }

    @Test
    fun the_badge_names_debug_or_release_and_the_type() {
        assertEquals("DEBUG APK", ArtifactTexts.badge(entry("a.apk", BuildKind.DEBUG)))
        assertEquals("RELEASE AAB", ArtifactTexts.badge(entry("a.aab", BuildKind.RELEASE)))
        assertEquals("APK", ArtifactTexts.badge(entry("a.apk", BuildKind.UNKNOWN)))
    }

    @Test
    fun deleting_a_release_file_gets_the_stronger_warning() {
        assertTrue(ArtifactTexts.deleteWarning(entry("a.apk", BuildKind.RELEASE)).contains("RELEASE"))
        assertFalse(ArtifactTexts.deleteWarning(entry("a.apk", BuildKind.DEBUG)).contains("RELEASE"))
    }

    @Test
    fun signing_and_integrity_lines_say_what_was_and_was_not_checked() {
        val reader = ApkPackageReader { ApkPackageResult.Unavailable }
        val assessor = ArtifactAssessor(ArtifactAnalyzer(), reader, ArtifactRegistry(File(tmp.root, "m")))
        val signed = File(tmp.root, "s.apk").apply { writeBytes(withSigningBlock(plainApk(), b64(REL_CERT_B64))) }
        val r = assessor.assess("b1", signed, null)
        assertTrue(ArtifactTexts.signingLine(r).endsWith("not verified"))
        assertTrue(ArtifactTexts.schemesLine(r)!!.contains("v2"))
        assertTrue(ArtifactTexts.integrityLine(r).startsWith("Integrity: Checksum recorded now"))
        val unsigned = assessor.assess("b2", File(tmp.root, "u.apk").apply { writeBytes(plainApk()) }, null)
        assertEquals("Signing: Not signed", ArtifactTexts.signingLine(unsigned))
    }
}
