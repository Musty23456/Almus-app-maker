package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.service.artifacts.ApkPackageReader
import com.almus.appcreatorpro.service.artifacts.ApkPackageResult
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.ArtifactAnalyzer
import com.almus.appcreatorpro.service.artifacts.ArtifactAssessor
import com.almus.appcreatorpro.service.artifacts.ArtifactCatalog
import com.almus.appcreatorpro.service.artifacts.ArtifactEntry
import com.almus.appcreatorpro.service.artifacts.ArtifactFilter
import com.almus.appcreatorpro.service.artifacts.ArtifactOrganizer
import com.almus.appcreatorpro.service.artifacts.ArtifactRecord
import com.almus.appcreatorpro.service.artifacts.ArtifactRegistry
import com.almus.appcreatorpro.service.artifacts.ArtifactSort
import com.almus.appcreatorpro.service.artifacts.ArtifactType
import com.almus.appcreatorpro.service.artifacts.BuildContext
import com.almus.appcreatorpro.service.artifacts.BuildIntent
import com.almus.appcreatorpro.service.artifacts.BuildKind
import com.almus.appcreatorpro.service.artifacts.Checksums
import com.almus.appcreatorpro.service.artifacts.ExportOutcome
import com.almus.appcreatorpro.service.artifacts.IntegrityState
import com.almus.appcreatorpro.service.artifacts.KnownSigningKeys
import com.almus.appcreatorpro.service.artifacts.StoredApk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class Phase33CatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val relSigner = com.almus.appcreatorpro.service.artifacts.SignerInfo.fromDer(b64(REL_CERT_B64))!!

    private class CountingReader : ApkPackageReader {
        var calls = 0
        override fun read(file: File): ApkPackageResult {
            calls++
            return ApkPackageResult.Parsed("com.example.shop", "1.0", 1L, false, 24, 35, true, listOf(com.almus.appcreatorpro.service.artifacts.SignerInfo.fromDer(b64(REL_CERT_B64))!!))
        }
    }

    private val reader = CountingReader()
    private val store by lazy { ApkStore(File(tmp.root, "apks")) }
    private val registry by lazy { ArtifactRegistry(File(tmp.root, "meta")) }
    private val catalog by lazy { ArtifactCatalog(store, registry, ArtifactAssessor(ArtifactAnalyzer(), reader, registry, KnownSigningKeys.NONE, 34) { 1_800_000_000_000L }) }

    private fun save(buildId: String, name: String, bytes: ByteArray, modified: Long? = null): File {
        val f = File(store.dirFor(buildId), name)
        f.parentFile.mkdirs()
        f.writeBytes(bytes)
        if (modified != null) f.setLastModified(modified)
        return f
    }

    private val signedApk get() = withSigningBlock(plainApk(), b64(REL_CERT_B64))

    // ---------------------------------------------------------------- listing and checking

    @Test
    fun listing_joins_the_build_and_orders_newest_first() {
        save("b1", "app-debug.apk", signedApk, 1_000_000L)
        save("b2", "app-release.aab", b64(SIGNED_RELEASE_AAB_B64), 2_000_000L)
        val ctx = mapOf("b2" to BuildContext("Shop", null))
        val list = catalog.list(ctx)
        assertEquals(listOf("app-release.aab", "app-debug.apk"), list.map { it.fileName })
        assertEquals("Shop", list[0].projectName)
        assertEquals("Unknown project", list[1].projectName)
        assertEquals(ArtifactType.AAB, list[0].type)
        assertNull("nothing is claimed before a check", list[0].report)
    }

    @Test
    fun a_check_is_remembered_until_the_file_changes_or_a_recheck_is_forced() {
        val f = save("b1", "app.apk", signedApk)
        val entry = catalog.list(emptyMap()).single()
        catalog.assess(entry)
        catalog.assess(entry)
        assertEquals("the second look reuses the first result", 1, reader.calls)
        catalog.assess(entry, force = true)
        assertEquals(2, reader.calls)
        f.appendBytes(byteArrayOf(0))
        f.setLastModified(f.lastModified() + 5_000)
        catalog.assess(entry)
        assertEquals("a changed file is looked at again", 3, reader.calls)
    }

    @Test
    fun an_earlier_check_comes_back_attached_to_the_next_listing() {
        save("b1", "app.apk", signedApk)
        catalog.assess(catalog.list(emptyMap()).single())
        assertNotNull(catalog.list(emptyMap()).single().report)
        catalog.forgetAll()
        assertNull(catalog.list(emptyMap()).single().report)
    }

    // ---------------------------------------------------------------- sharing gate

    @Test
    fun sharing_is_blocked_until_checked_and_for_changed_or_damaged_files() {
        val f = save("b1", "app.apk", signedApk)
        val entry = catalog.list(emptyMap()).single()
        assertTrue(entry.shareBlockedReason!!.contains("still being checked"))

        val ok = catalog.assess(entry)
        assertNull(ok.shareBlockedReason)

        f.appendBytes(byteArrayOf(9))
        f.setLastModified(f.lastModified() + 5_000)
        val changed = catalog.assess(entry)
        assertEquals(IntegrityState.MODIFIED, changed.report!!.integrity.state)
        assertTrue(changed.shareBlockedReason!!.contains("changed"))

        val bad = save("b2", "bad.apk", "nope".toByteArray())
        val damaged = catalog.assess(catalog.list(emptyMap()).first { it.fileName == bad.name })
        assertTrue(damaged.shareBlockedReason!!.contains("damaged"))
    }

    // ---------------------------------------------------------------- export

    @Test
    fun an_export_is_byte_exact_and_reports_its_checksum() {
        val bytes = signedApk
        save("b1", "app.apk", bytes)
        val entry = catalog.assess(catalog.list(emptyMap()).single())
        val out = ByteArrayOutputStream()
        val result = catalog.export(entry, entry.report!!.integrity.sha256, out)
        assertArrayEquals(bytes, out.toByteArray())
        result as ExportOutcome.Saved
        assertEquals(Checksums.sha256(bytes), result.sha256)
        assertEquals(bytes.size.toLong(), result.bytes)
    }

    @Test
    fun an_export_of_a_file_that_changed_since_the_check_is_reported_as_changed() {
        save("b1", "app.apk", signedApk)
        val entry = catalog.assess(catalog.list(emptyMap()).single())
        val result = catalog.export(entry, "0".repeat(64), ByteArrayOutputStream())
        assertTrue(result is ExportOutcome.Changed)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun deleting_removes_the_file_its_checksum_record_and_an_empty_folder() {
        val f = save("b1", "app.apk", signedApk)
        val entry = catalog.assess(catalog.list(emptyMap()).single())
        assertNotNull(registry.get("b1", "app.apk"))
        assertTrue(catalog.delete(entry))
        assertFalse(f.exists())
        assertNull(registry.get("b1", "app.apk"))
        assertFalse(store.dirFor("b1").exists())
        assertTrue(catalog.list(emptyMap()).isEmpty())
    }

    @Test
    fun deleting_one_file_keeps_its_sibling() {
        save("b1", "app.apk", signedApk)
        val aab = save("b1", "app.aab", b64(SIGNED_RELEASE_AAB_B64))
        val apk = catalog.list(emptyMap()).first { it.fileName == "app.apk" }
        val summary = catalog.deleteMany(listOf(apk))
        assertEquals(1, summary.deleted)
        assertEquals(0, summary.failed)
        assertTrue(aab.exists())
        assertTrue(store.dirFor("b1").exists())
    }

    // ---------------------------------------------------------------- download baseline

    @Test
    fun a_downloaded_file_gets_a_download_checksum_that_later_checks_use() {
        val f = save("b1", "app.apk", signedApk)
        ArtifactCatalog.recordDownload(registry, "b1", f, 5L)
        val rec = registry.get("b1", "app.apk")!!
        assertEquals("download", rec.source)
        assertEquals(Checksums.sha256(f), rec.sha256)
        val entry = catalog.assess(catalog.list(emptyMap()).single())
        assertEquals(IntegrityState.VERIFIED, entry.report!!.integrity.state)
        assertEquals("download", entry.report!!.integrity.recordedSource)
    }

    // ---------------------------------------------------------------- registry hygiene

    @Test
    fun the_registry_keeps_only_a_checksum_a_size_a_time_and_a_source() {
        registry.put("b1", "app.apk", ArtifactRecord("ab".repeat(32), 10L, 5L, "download"))
        val text = File(tmp.root, "meta/b1/app.apk.json").readText()
        val keys = com.almus.appcreatorpro.service.github.MiniJson.parseObject(text).keys
        assertEquals(setOf("sha256", "sizeBytes", "recordedAtMillis", "source"), keys)
    }

    @Test
    fun the_registry_refuses_names_that_could_escape_its_folder() {
        assertFalse(registry.put("../x", "app.apk", ArtifactRecord("a", 1, 1, "download")))
        assertFalse(registry.put("b1", "../app.apk", ArtifactRecord("a", 1, 1, "download")))
        assertNull(registry.get("b1", "a/b.apk"))
        assertFalse(File(tmp.root, "x").exists())
    }

    @Test
    fun a_broken_record_is_ignored_not_trusted() {
        File(tmp.root, "meta/b1").mkdirs()
        File(tmp.root, "meta/b1/app.apk.json").writeText("{not json")
        assertNull(registry.get("b1", "app.apk"))
        File(tmp.root, "meta/b1/app.apk.json").writeText("{\"sizeBytes\":5}")
        assertNull(registry.get("b1", "app.apk"))
    }

    @Test
    fun the_store_copy_with_checksum_is_exact_and_ids_are_validated() {
        val bytes = plainApk("abc")
        val f = save("b1", "app.apk", bytes)
        val out = ByteArrayOutputStream()
        val r = store.copyToWithChecksum(StoredApk("b1", f), out)
        assertArrayEquals(bytes, out.toByteArray())
        assertEquals(Checksums.sha256(bytes), r.sha256)
        assertTrue(ApkStore.validBuildId("abc_DEF-1"))
        assertFalse(ApkStore.validBuildId("../x"))
        assertFalse(ApkStore.validBuildId(""))
    }

    // ---------------------------------------------------------------- organizing

    private fun entry(project: String, name: String, saved: Long, size: Long = 100L, kind: BuildKind = BuildKind.UNKNOWN): ArtifactEntry {
        val ctx = BuildContext(project, BuildIntent("x", kind, null, null, null, null))
        return ArtifactEntry("b-$project", StoredApk("b-$project", File(tmp.root, name)), project, ctx, size, saved)
    }

    @Test
    fun items_are_grouped_by_project_and_sorted() {
        val items = listOf(
            entry("Shop", "a-debug.apk", 10, 300, BuildKind.DEBUG),
            entry("Shop", "b-release.apk", 30, 100, BuildKind.RELEASE),
            entry("Notes", "n-release.aab", 20, 900, BuildKind.RELEASE),
        )
        val newest = ArtifactOrganizer.arrange(items, ArtifactFilter(), ArtifactSort.NEWEST)
        assertEquals(listOf("Shop", "Notes"), newest.map { it.projectName })
        assertEquals(listOf("b-release.apk", "a-debug.apk"), newest[0].items.map { it.fileName })
        val largest = ArtifactOrganizer.arrange(items, ArtifactFilter(), ArtifactSort.LARGEST)
        assertEquals("Notes", largest[0].projectName)
        val name = ArtifactOrganizer.arrange(items, ArtifactFilter(), ArtifactSort.NAME)
        assertEquals(listOf("Notes", "Shop"), name.map { it.projectName })
        val oldest = ArtifactOrganizer.arrange(items, ArtifactFilter(), ArtifactSort.OLDEST)
        assertEquals("a-debug.apk", oldest[0].items[0].fileName)
    }

    @Test
    fun filters_by_type_kind_and_text() {
        val items = listOf(
            entry("Shop", "a-debug.apk", 10, kind = BuildKind.DEBUG),
            entry("Shop", "b-release.apk", 30, kind = BuildKind.RELEASE),
            entry("Notes", "n-release.aab", 20, kind = BuildKind.RELEASE),
        )
        fun names(f: ArtifactFilter) = ArtifactOrganizer.arrange(items, f, ArtifactSort.NAME).flatMap { it.items }.map { it.fileName }
        assertEquals(listOf("n-release.aab"), names(ArtifactFilter(type = ArtifactType.AAB)))
        assertEquals(listOf("a-debug.apk"), names(ArtifactFilter(kind = BuildKind.DEBUG)))
        assertEquals(listOf("n-release.aab", "b-release.apk"), names(ArtifactFilter(kind = BuildKind.RELEASE)))
        assertEquals(listOf("n-release.aab"), names(ArtifactFilter(query = " notes ")))
        assertTrue(names(ArtifactFilter(query = "zzz")).isEmpty())
        assertTrue(ArtifactFilter(query = "x").isActive)
        assertFalse(ArtifactFilter().isActive)
    }

    @Test
    fun clean_up_keeps_the_newest_of_every_project_type_and_kind() {
        val items = listOf(
            entry("Shop", "r1.apk", 10, 10, BuildKind.RELEASE),
            entry("Shop", "r2.apk", 20, 20, BuildKind.RELEASE),
            entry("Shop", "r3.apk", 30, 30, BuildKind.RELEASE),
            entry("Shop", "d1.apk", 5, 50, BuildKind.DEBUG),
            entry("Shop", "d2.apk", 6, 60, BuildKind.DEBUG),
            entry("Shop", "b1.aab", 7, 70, BuildKind.RELEASE),
        )
        val plan = ArtifactOrganizer.cleanupPlan(items, 1)
        assertEquals(setOf("r1.apk", "r2.apk", "d1.apk"), plan.delete.map { it.fileName }.toSet())
        assertEquals(setOf("r3.apk", "d2.apk", "b1.aab"), plan.keep.map { it.fileName }.toSet())
        assertEquals(10L + 20L + 50L, plan.bytes)
        val keepTwo = ArtifactOrganizer.cleanupPlan(items, 2)
        assertEquals(listOf("r1.apk"), keepTwo.delete.map { it.fileName })
    }

    @Test
    fun clean_up_can_never_propose_the_newest_release_even_when_asked_to_keep_zero() {
        val items = listOf(entry("Shop", "r1.apk", 10, kind = BuildKind.RELEASE), entry("Shop", "r2.apk", 20, kind = BuildKind.RELEASE))
        val plan = ArtifactOrganizer.cleanupPlan(items, 0)
        assertEquals(listOf("r1.apk"), plan.delete.map { it.fileName })
        assertTrue(ArtifactOrganizer.cleanupPlan(items.take(1), 0).delete.isEmpty())
        assertTrue(ArtifactOrganizer.cleanupPlan(emptyList(), 3).delete.isEmpty())
    }

    @Test
    fun a_relsigner_exists_for_the_fixtures() {
        assertEquals(REL_CERT_SHA256, relSigner.sha256)
    }
}
