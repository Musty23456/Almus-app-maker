package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.service.artifacts.ApkPackageReader
import com.almus.appcreatorpro.service.artifacts.ApkPackageResult
import com.almus.appcreatorpro.service.artifacts.ArtifactAnalyzer
import com.almus.appcreatorpro.service.artifacts.ArtifactAssessor
import com.almus.appcreatorpro.service.artifacts.ArtifactRecord
import com.almus.appcreatorpro.service.artifacts.ArtifactRegistry
import com.almus.appcreatorpro.service.artifacts.ArtifactReport
import com.almus.appcreatorpro.service.artifacts.ArtifactType
import com.almus.appcreatorpro.service.artifacts.BuildContext
import com.almus.appcreatorpro.service.artifacts.BuildIntent
import com.almus.appcreatorpro.service.artifacts.BuildKind
import com.almus.appcreatorpro.service.artifacts.Checksums
import com.almus.appcreatorpro.service.artifacts.InstallStatus
import com.almus.appcreatorpro.service.artifacts.IntegrityState
import com.almus.appcreatorpro.service.artifacts.KnownSigningKeys
import com.almus.appcreatorpro.service.artifacts.SignerInfo
import com.almus.appcreatorpro.service.artifacts.SigningStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Phase33AssessorTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val relSigner = SignerInfo.fromDer(b64(REL_CERT_B64))!!
    private val dbgSigner = SignerInfo.fromDer(b64(DBG_CERT_B64))!!

    private class FakeReader(var result: ApkPackageResult) : ApkPackageReader {
        var calls = 0
        override fun read(file: File): ApkPackageResult {
            calls++
            return result
        }
    }

    private fun parsed(
        debuggable: Boolean,
        verified: Boolean = true,
        signers: List<SignerInfo> = listOf(relSigner),
        minSdk: Int? = 24,
        versionCode: Long = 7,
    ) = ApkPackageResult.Parsed("com.example.shop", "1.2.3", versionCode, debuggable, minSdk, 35, verified, if (verified) signers else emptyList())

    private fun registry() = ArtifactRegistry(File(tmp.root, "meta"))

    private fun assessor(
        reader: ApkPackageReader,
        registry: ArtifactRegistry = registry(),
        keys: KnownSigningKeys = KnownSigningKeys.NONE,
        deviceSdk: Int? = 34,
        now: Long = 1_800_000_000_000L,
    ) = ArtifactAssessor(ArtifactAnalyzer(), reader, registry, keys, deviceSdk) { now }

    private fun write(name: String, bytes: ByteArray): File = File(tmp.root, name).apply { writeBytes(bytes) }

    private fun intent(kind: BuildKind, type: ArtifactType = ArtifactType.APK, versionCode: Long? = null, pkg: String? = null) =
        BuildContext("Shop", BuildIntent("$kind ${type.label}", kind, type, pkg, null, versionCode))

    private val signedApk get() = withSigningBlock(plainApk(), b64(REL_CERT_B64))

    // ---------------------------------------------------------------- APK: installable only when checked

    @Test
    fun a_signed_apk_that_android_verified_is_called_installable_and_says_who_checked() {
        val keys = KnownSigningKeys { fp -> if (fp == REL_CERT_SHA256) "Shop key" else null }
        val r = assessor(FakeReader(parsed(debuggable = false)), keys = keys).assess("b1", write("app-release.apk", signedApk), null)
        assertEquals(InstallStatus.VERIFIED_INSTALLABLE, r.install.status)
        assertEquals(SigningStatus.OWN_KEY, r.signing.status)
        assertEquals("Shop key", r.signing.matchedKeyLabel)
        assertTrue(r.signing.verified)
        assertEquals("Android", r.signing.verifiedBy)
        assertEquals(BuildKind.RELEASE, r.kind)
        assertEquals("the file", r.kindSource)
        assertEquals("com.example.shop", r.packageName)
        assertEquals("1.2.3", r.versionName)
        assertEquals(7L, r.versionCode)
        assertTrue(r.detailsFromFile)
    }

    @Test
    fun an_unsigned_apk_is_never_called_installable() {
        val reader = FakeReader(parsed(debuggable = false, verified = false))
        val r = assessor(reader).assess("b1", write("app.apk", plainApk()), null)
        assertEquals(SigningStatus.UNSIGNED, r.signing.status)
        assertEquals(InstallStatus.NOT_INSTALLABLE, r.install.status)
        assertTrue(r.install.headline.contains("not signed"))
    }

    @Test
    fun when_android_cannot_be_asked_nothing_is_claimed() {
        val r = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b1", write("app.apk", signedApk), null)
        assertEquals(InstallStatus.NOT_CHECKED, r.install.status)
        assertFalse(r.signing.verified)
        assertTrue("a found-but-unverified signature must be flagged", r.warnings.any { it.contains("could not be verified") })
    }

    @Test
    fun an_apk_android_rejects_is_not_installable() {
        val r = assessor(FakeReader(ApkPackageResult.Rejected)).assess("b1", write("app.apk", signedApk), null)
        assertEquals(InstallStatus.NOT_INSTALLABLE, r.install.status)
    }

    @Test
    fun a_signature_android_could_not_verify_is_not_installable() {
        val r = assessor(FakeReader(parsed(debuggable = false, verified = false))).assess("b1", write("app.apk", signedApk), null)
        assertEquals(InstallStatus.NOT_INSTALLABLE, r.install.status)
        assertFalse(r.signing.verified)
    }

    @Test
    fun an_apk_needing_a_newer_android_than_this_phone_is_not_installable_here() {
        val r = assessor(FakeReader(parsed(debuggable = false, minSdk = 35)), deviceSdk = 30).assess("b1", write("app.apk", signedApk), null)
        assertEquals(InstallStatus.NOT_INSTALLABLE, r.install.status)
        assertTrue(r.install.detail.contains("35"))
    }

    @Test
    fun a_damaged_apk_is_not_installable_and_android_is_not_even_asked() {
        val reader = FakeReader(parsed(debuggable = false))
        val r = assessor(reader).assess("b1", write("app.apk", "garbage".toByteArray()), null)
        assertEquals(IntegrityState.CORRUPT, r.integrity.state)
        assertEquals(InstallStatus.NOT_INSTALLABLE, r.install.status)
        assertEquals(0, reader.calls)
        assertTrue(r.integrity.blocksSharing)
    }

    // ---------------------------------------------------------------- debug versus release

    @Test
    fun a_debuggable_apk_is_a_debug_build_and_says_so() {
        val r = assessor(FakeReader(parsed(debuggable = true, signers = listOf(dbgSigner)))).assess("b1", write("app.apk", withSigningBlock(plainApk(), b64(DBG_CERT_B64))), null)
        assertEquals(BuildKind.DEBUG, r.kind)
        assertEquals(SigningStatus.DEBUG_KEY, r.signing.status)
        assertTrue(r.warnings.any { it.startsWith("Debug build") })
    }

    @Test
    fun the_file_outranks_the_build_settings_and_a_disagreement_is_a_warning() {
        val r = assessor(FakeReader(parsed(debuggable = true, signers = listOf(dbgSigner))))
            .assess("b1", write("app.apk", withSigningBlock(plainApk(), b64(DBG_CERT_B64))), intent(BuildKind.RELEASE))
        assertEquals(BuildKind.DEBUG, r.kind)
        assertTrue(r.warnings.any { it.contains("asked for a release build") && it.contains("debug build") })
        assertTrue("a release that carries the debug key is the dangerous case", r.warnings.any { it.contains("signed with the Android debug key") })
    }

    @Test
    fun without_file_evidence_the_build_settings_decide_and_the_file_name_is_the_last_resort() {
        val a = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b1", write("x.apk", plainApk()), intent(BuildKind.RELEASE))
        assertEquals(BuildKind.RELEASE, a.kind)
        assertEquals("the build settings", a.kindSource)
        val b = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b2", write("app-debug.apk", plainApk()), null)
        assertEquals(BuildKind.DEBUG, b.kind)
        assertEquals("the file name", b.kindSource)
        val c = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b3", write("whatever.apk", plainApk()), null)
        assertEquals(BuildKind.UNKNOWN, c.kind)
    }

    @Test
    fun an_unsigned_release_is_flagged() {
        val r = assessor(FakeReader(parsed(debuggable = false, verified = false))).assess("b1", write("app-release.apk", plainApk()), intent(BuildKind.RELEASE))
        assertTrue(r.warnings.any { it.contains("release build is not signed") })
    }

    // ---------------------------------------------------------------- bundles

    @Test
    fun a_bundle_is_never_installable_but_its_signature_and_details_are_read() {
        val r = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b1", write("app-release.aab", b64(SIGNED_RELEASE_AAB_B64)), null)
        assertEquals(ArtifactType.AAB, r.type)
        assertEquals(InstallStatus.BUNDLE_ONLY, r.install.status)
        assertTrue(r.signing.verified)
        assertEquals("the bundle signature check", r.signing.verifiedBy)
        assertEquals("com.example.shop", r.packageName)
        assertEquals("1.2.3", r.versionName)
        assertEquals(7L, r.versionCode)
        assertTrue(r.detailsFromFile)
        assertEquals(BuildKind.RELEASE, r.kind)
        assertEquals(SigningStatus.OTHER_KEY, r.signing.status)
    }

    @Test
    fun a_debug_bundle_is_marked_debug_and_warned_about() {
        val r = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b1", write("app-debug.aab", b64(SIGNED_DEBUG_AAB_B64)), null)
        assertEquals(BuildKind.DEBUG, r.kind)
        assertEquals(SigningStatus.DEBUG_KEY, r.signing.status)
        assertTrue(r.warnings.any { it.contains("Google Play does not accept") })
    }

    @Test
    fun a_tampered_bundle_does_not_count_as_verified() {
        val bytes = rewriteZip(b64(SIGNED_RELEASE_AAB_B64), { n, d -> if (n.endsWith("classes.dex")) "x".toByteArray() else d })
        val r = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b1", write("app.aab", bytes), null)
        assertFalse(r.signing.verified)
        assertTrue(r.warnings.any { it.contains("could not be verified") })
    }

    @Test
    fun an_unsigned_bundle_says_not_signed() {
        val r = assessor(FakeReader(ApkPackageResult.Unavailable)).assess("b1", write("app.aab", b64(UNSIGNED_AAB_B64)), null)
        assertEquals(SigningStatus.UNSIGNED, r.signing.status)
    }

    // ---------------------------------------------------------------- integrity

    @Test
    fun the_first_check_records_a_checksum_and_the_second_verifies_it() {
        val reg = registry()
        val f = write("app.apk", signedApk)
        val a = assessor(FakeReader(parsed(debuggable = false)), reg)
        val first = a.assess("b1", f, null)
        assertEquals(IntegrityState.RECORDED, first.integrity.state)
        assertNotNull(reg.get("b1", "app.apk"))
        val second = a.assess("b1", f, null)
        assertEquals(IntegrityState.VERIFIED, second.integrity.state)
        assertEquals(Checksums.sha256(f), second.integrity.sha256)
    }

    @Test
    fun a_file_changed_after_saving_is_flagged_and_the_baseline_is_never_overwritten() {
        val reg = registry()
        val f = write("app.apk", signedApk)
        reg.put("b1", "app.apk", ArtifactRecord(Checksums.sha256(f), f.length(), 1L, "download"))
        f.appendBytes(byteArrayOf(1, 2, 3))
        val a = assessor(FakeReader(parsed(debuggable = false)), reg)
        val r1 = a.assess("b1", f, null)
        assertEquals(IntegrityState.MODIFIED, r1.integrity.state)
        assertTrue(r1.integrity.blocksSharing)
        assertTrue(r1.integrity.problems.first().contains("downloaded"))
        assertEquals(InstallStatus.NOT_INSTALLABLE, r1.install.status)
        assertTrue(r1.warnings.any { it.contains("different from the one that was saved") })
        val r2 = a.assess("b1", f, null)
        assertEquals("a second look must not accept the changed file as the new normal", IntegrityState.MODIFIED, r2.integrity.state)
    }

    @Test
    fun a_corrupt_file_never_becomes_the_baseline() {
        val reg = registry()
        assessor(FakeReader(ApkPackageResult.Unavailable), reg).assess("b1", write("bad.apk", "x".toByteArray()), null)
        assertNull(reg.get("b1", "bad.apk"))
    }

    // ---------------------------------------------------------------- details

    @Test
    fun build_settings_fill_in_details_only_when_the_file_cannot_tell() {
        val r = assessor(FakeReader(ApkPackageResult.Unavailable))
            .assess("b1", write("app.apk", plainApk()), BuildContext("Shop", BuildIntent("Debug APK", BuildKind.DEBUG, ArtifactType.APK, "com.from.settings", "9.9", 99L)))
        assertEquals("com.from.settings", r.packageName)
        assertEquals("9.9", r.versionName)
        assertEquals(99L, r.versionCode)
        assertFalse(r.detailsFromFile)
        assertEquals("Debug APK", r.buildVariantLabel)
    }

    @Test
    fun a_version_that_differs_from_the_build_settings_is_reported() {
        val r = assessor(FakeReader(parsed(debuggable = false, versionCode = 7)))
            .assess("b1", write("app.apk", signedApk), intent(BuildKind.RELEASE, versionCode = 70L, pkg = "com.other"))
        assertTrue(r.warnings.any { it.contains("version code 70") && it.contains("contains 7") })
        assertTrue(r.warnings.any { it.contains("com.other") })
    }

    @Test
    fun an_expired_certificate_is_reported() {
        val far = 4_000_000_000_000L // the year 2096: after the test certificate's end
        val r = assessor(FakeReader(parsed(debuggable = false)), now = far).assess("b1", write("app.apk", signedApk), null)
        assertTrue(r.warnings.any { it.contains("certificate has expired") })
    }

    @Test
    fun the_copyable_details_hold_public_data_only() {
        val r = assessor(FakeReader(parsed(debuggable = false))).assess("b1", write("app.apk", signedApk), null)
        val text = r.toPlainText()
        assertTrue(text.contains(REL_CERT_SHA256))
        assertTrue(text.contains(r.integrity.sha256!!))
        for (word in listOf("password", "private", "keystore", "secret", "BEGIN")) {
            assertFalse("details must not mention '$word'", text.lowercase().contains(word.lowercase()))
        }
    }

    @Suppress("unused")
    private fun keep(r: ArtifactReport) = r
}
