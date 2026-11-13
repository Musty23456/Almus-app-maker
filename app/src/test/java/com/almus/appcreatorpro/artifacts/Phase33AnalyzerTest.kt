package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.service.artifacts.AnalyzerLimits
import com.almus.appcreatorpro.service.artifacts.ApkSigningBlock
import com.almus.appcreatorpro.service.artifacts.ArtifactAnalyzer
import com.almus.appcreatorpro.service.artifacts.ArtifactType
import com.almus.appcreatorpro.service.artifacts.BundleManifestReader
import com.almus.appcreatorpro.service.artifacts.Checksums
import com.almus.appcreatorpro.service.artifacts.SignatureScheme
import com.almus.appcreatorpro.service.artifacts.SignerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Phase33AnalyzerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val analyzer = ArtifactAnalyzer()

    private fun file(name: String, bytes: ByteArray): File = File(tmp.root, name).apply { writeBytes(bytes) }

    // ---------------------------------------------------------------- APK structure

    @Test
    fun a_plain_apk_is_whole_unsigned_and_has_a_checksum() {
        val bytes = plainApk()
        val f = file("app.apk", bytes)
        val a = analyzer.analyze(f, ArtifactType.APK)
        assertTrue(a.structureOk)
        assertTrue(a.structureProblems.isEmpty())
        assertFalse(a.hasSignature)
        assertTrue(a.signers.isEmpty())
        assertEquals(Checksums.sha256(bytes), a.sha256)
        assertEquals(bytes.size.toLong(), a.sizeBytes)
    }

    @Test
    fun an_apk_without_its_manifest_is_not_whole() {
        val f = file("app.apk", buildZip("classes.dex" to "x".toByteArray()))
        val a = analyzer.analyze(f, ArtifactType.APK)
        assertFalse(a.structureOk)
        assertTrue(a.structureProblems.single().contains("AndroidManifest.xml"))
    }

    @Test
    fun text_that_is_not_a_zip_is_reported_not_thrown() {
        val f = file("app.apk", "this is not an archive".toByteArray())
        val a = analyzer.analyze(f, ArtifactType.APK)
        assertFalse(a.structureOk)
        assertNotNull(a.sha256)
        assertTrue(a.structureProblems.isNotEmpty())
    }

    @Test
    fun a_damaged_entry_is_found_by_the_checksum_of_its_data() {
        val good = plainApk("classes-classes-classes")
        // Change one byte of the stored payload (the entry is tiny, so the damage lands in its data).
        val damaged = good.copyOf()
        val at = String(good, Charsets.ISO_8859_1).indexOf("classes.dex") + "classes.dex".length + 2
        damaged[at] = (damaged[at].toInt() xor 0x55).toByte()
        val a = analyzer.analyze(file("app.apk", damaged), ArtifactType.APK)
        assertFalse("damage must be noticed", a.structureOk)
    }

    @Test
    fun an_archive_that_expands_too_much_is_refused_safely() {
        val big = buildZip("AndroidManifest.xml" to ByteArray(10), "classes.dex" to ByteArray(500_000))
        val a = ArtifactAnalyzer(AnalyzerLimits(maxUncompressedBytes = 1000)).analyze(file("bomb.apk", big), ArtifactType.APK)
        assertFalse(a.structureOk)
        assertTrue(a.structureProblems.any { it.contains("more data") })
    }

    @Test
    fun a_missing_file_is_reported_without_a_crash() {
        val a = analyzer.analyze(File(tmp.root, "gone.apk"), ArtifactType.APK)
        assertFalse(a.structureOk)
        assertNull(a.sha256)
    }

    // ---------------------------------------------------------------- APK signing block

    @Test
    fun a_v2_signing_block_is_found_and_its_certificate_is_read() {
        val signed = withSigningBlock(plainApk(), b64(REL_CERT_B64))
        val a = analyzer.analyze(file("app.apk", signed), ArtifactType.APK)
        assertTrue("the zip must still be whole after the block was inserted", a.structureOk)
        assertEquals(listOf(SignatureScheme.V2), a.schemes)
        val signer = a.signers.single()
        assertEquals(REL_CERT_SHA256, signer.sha256)
        assertTrue(signer.subject.contains("Test Release"))
        assertFalse(signer.isAndroidDebugCertificate)
    }

    @Test
    fun v2_v3_and_v31_are_all_reported() {
        val signed = withSigningBlock(plainApk(), b64(REL_CERT_B64), listOf(ApkSigningBlock.ID_V2, ApkSigningBlock.ID_V3, ApkSigningBlock.ID_V31))
        val a = analyzer.analyze(file("app.apk", signed), ArtifactType.APK)
        assertEquals(listOf(SignatureScheme.V2, SignatureScheme.V3, SignatureScheme.V3_1), a.schemes)
        assertEquals(1, a.signers.size) // the same certificate is listed once
    }

    @Test
    fun the_android_debug_certificate_is_recognised() {
        val signed = withSigningBlock(plainApk(), b64(DBG_CERT_B64))
        val a = analyzer.analyze(file("app.apk", signed), ArtifactType.APK)
        assertTrue(a.signers.single().isAndroidDebugCertificate)
    }

    @Test
    fun a_damaged_signing_block_gives_a_problem_and_never_a_crash() {
        val signed = withSigningBlock(plainApk(), b64(REL_CERT_B64))
        // Make the first pair claim to be enormous.
        val damaged = signed.copyOf()
        val pairLenAt = findFirstPairLength(damaged)
        damaged[pairLenAt + 6] = 0x7F
        val a = analyzer.analyze(file("app.apk", damaged), ArtifactType.APK)
        assertTrue(a.signatureProblems.isNotEmpty())
        assertTrue(a.signers.isEmpty())
    }

    /** Offset of the first pair's length field: the leading size field of the block is 8 bytes before it. */
    private fun findFirstPairLength(zip: ByteArray): Int {
        val text = String(zip, Charsets.ISO_8859_1)
        val magicAt = text.indexOf("APK Sig Block 42")
        // The block ends with: size(8) + magic(16). Its leading size is the same number; search for it from the start.
        val size = (0 until 8).fold(0L) { acc, k -> acc or ((zip[magicAt - 8 + k].toLong() and 0xFF) shl (8 * k)) }
        val start = magicAt + 16 - (size + 8).toInt()
        return start + 8
    }

    @Test
    fun a_zip_without_a_block_has_no_v2_scheme() {
        val a = analyzer.analyze(file("app.apk", plainApk()), ArtifactType.APK)
        assertTrue(a.schemes.isEmpty())
    }

    // ---------------------------------------------------------------- AAB

    @Test
    fun a_signed_release_bundle_is_verified_and_its_manifest_is_read() {
        val a = analyzer.analyze(file("app-release.aab", b64(SIGNED_RELEASE_AAB_B64)), ArtifactType.AAB)
        assertTrue(a.structureProblems.toString(), a.structureOk)
        assertEquals(listOf(SignatureScheme.V1), a.schemes)
        assertEquals(true, a.jarSignatureVerified)
        assertEquals(REL_CERT_SHA256, a.signers.single().sha256)
        val m = a.bundleManifest!!
        assertEquals("com.example.shop", m.packageName)
        assertEquals(7L, m.versionCode)
        assertEquals("1.2.3", m.versionName)
        assertEquals(24, m.minSdk)
        assertEquals(35, m.targetSdk)
        assertNull(m.debuggable)
    }

    @Test
    fun a_debug_bundle_says_it_is_debuggable_and_uses_the_debug_certificate() {
        val a = analyzer.analyze(file("app-debug.aab", b64(SIGNED_DEBUG_AAB_B64)), ArtifactType.AAB)
        assertEquals(true, a.bundleManifest!!.debuggable)
        assertTrue(a.signers.single().isAndroidDebugCertificate)
        assertEquals(true, a.jarSignatureVerified)
    }

    @Test
    fun an_unsigned_bundle_has_nothing_to_verify() {
        val a = analyzer.analyze(file("app.aab", b64(UNSIGNED_AAB_B64)), ArtifactType.AAB)
        assertTrue(a.structureOk)
        assertFalse(a.hasSignature)
        assertNull(a.jarSignatureVerified)
    }

    @Test
    fun a_bundle_changed_after_signing_fails_verification() {
        val tampered = rewriteZip(b64(SIGNED_RELEASE_AAB_B64), { name, data ->
            if (name == "base/dex/classes.dex") "evil".toByteArray() else data
        })
        val a = analyzer.analyze(file("app.aab", tampered), ArtifactType.AAB)
        assertEquals(false, a.jarSignatureVerified)
        assertTrue(a.signatureProblems.isNotEmpty())
    }

    @Test
    fun a_file_added_after_signing_is_not_covered_and_fails_verification() {
        val extra = rewriteZip(b64(SIGNED_RELEASE_AAB_B64), { _, data -> data }, listOf("base/extra.bin" to "x".toByteArray()))
        val a = analyzer.analyze(file("app.aab", extra), ArtifactType.AAB)
        assertEquals(false, a.jarSignatureVerified)
    }

    @Test
    fun a_bundle_without_bundle_config_is_not_a_bundle() {
        val broken = rewriteZip(b64(UNSIGNED_AAB_B64), { name, data -> if (name == "BundleConfig.pb") null else data })
        val a = analyzer.analyze(file("app.aab", broken), ArtifactType.AAB)
        assertFalse(a.structureOk)
    }

    // ---------------------------------------------------------------- readers

    @Test
    fun the_manifest_reader_survives_rubbish() {
        assertNull(BundleManifestReader.parse(ByteArray(0)))
        assertNull(BundleManifestReader.parse(byteArrayOf(0x0A, 0x7F, 1, 2, 3)))
        assertNull(BundleManifestReader.parse(ByteArray(300) { 0xFF.toByte() }))
        assertNull(BundleManifestReader.parse("<manifest/>".toByteArray()))
    }

    @Test
    fun fingerprints_and_subjects_are_read_from_der() {
        val info = SignerInfo.fromDer(b64(REL_CERT_B64))!!
        assertEquals(REL_CERT_SHA256, info.sha256)
        assertNull(SignerInfo.fromDer(byteArrayOf(1, 2, 3)))
        assertEquals(SignerInfo.normalize("aa:bb:cc"), SignerInfo.normalize("AABBCC"))
    }

    @Test
    fun the_debug_certificate_name_check_ignores_spacing_and_case() {
        assertTrue(SignerInfo.isDebugSubject("C=US,O=Android,CN=Android Debug"))
        assertTrue(SignerInfo.isDebugSubject("cn=android debug, o=android"))
        assertFalse(SignerInfo.isDebugSubject("CN=Android Release,O=Android"))
        assertFalse(SignerInfo.isDebugSubject("CN=My Company"))
    }
}
