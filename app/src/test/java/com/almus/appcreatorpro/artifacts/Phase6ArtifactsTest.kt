package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.data.repository.InMemoryBuildStore
import com.almus.appcreatorpro.domain.build.BuildConfiguration
import com.almus.appcreatorpro.domain.build.BuildVariant
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.ArtifactDetails
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException.Kind
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloader
import com.almus.appcreatorpro.service.artifacts.ArtifactInspector
import com.almus.appcreatorpro.service.github.TokenProvider
import com.almus.appcreatorpro.testutil.FakeStreamTransport
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.testutil.Reply
import com.almus.appcreatorpro.testutil.fakeApk
import com.almus.appcreatorpro.testutil.zipOf
import com.almus.appcreatorpro.ui.UiState
import com.almus.appcreatorpro.ui.artifacts.ArtifactsViewModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

private fun fakeAab(): ByteArray = zipOf(
    "base/manifest/AndroidManifest.xml" to "<manifest/>".toByteArray(),
    "base/dex/classes.dex" to "dex".toByteArray(),
    "BundleConfig.pb" to "cfg".toByteArray(),
)

class Phase6ArtifactDownloadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signedUrl = "https://storage.example.net/blob/abc?sig=SECRETSIGNATURE&se=2026"
    private val transport = FakeStreamTransport()
    private val downloader = ArtifactDownloader(transport, TokenProvider { "ghu_TOKENTOKENTOKENTOKENTOKEN123456" })

    private suspend fun download(zip: ByteArray): List<File> {
        transport.reset(Reply.redirect(signedUrl), Reply.ok(zip))
        return downloader.download("musty", "builds", 42L, File(tmp.root, "apks/b1"), File(tmp.root, "apks/.tmp"))
    }

    private suspend fun failure(zip: ByteArray): ArtifactDownloadException {
        try {
            download(zip)
        } catch (e: ArtifactDownloadException) {
            return e
        }
        fail("expected a failure")
        throw IllegalStateException()
    }

    @Test
    fun an_aab_is_downloaded_verified_and_saved() = runTest {
        val aab = fakeAab()
        val files = download(zipOf("app-release-signed.aab" to aab))
        assertEquals(listOf("app-release-signed.aab"), files.map { it.name })
        assertArrayEquals(aab, files[0].readBytes())
    }

    @Test
    fun an_aab_without_the_base_manifest_is_rejected_and_nothing_is_saved() = runTest {
        val e = failure(zipOf("app.aab" to zipOf("AndroidManifest.xml" to "x".toByteArray())))
        assertEquals(Kind.CORRUPT, e.kind)
        assertTrue(File(tmp.root, "apks/b1").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun an_apk_is_still_accepted_and_an_apk_manifest_is_not_enough_for_an_aab() = runTest {
        assertEquals(listOf("app-debug.apk"), download(zipOf("app-debug.apk" to fakeApk())).map { it.name })
        assertEquals(Kind.CORRUPT, failure(zipOf("x.aab" to fakeApk())).kind)
    }

    @Test
    fun an_artifact_with_neither_apk_nor_aab_says_so() = runTest {
        val e = failure(zipOf("build.log" to "text".toByteArray()))
        assertEquals(Kind.NO_APK, e.kind)
        assertTrue(e.message!!.contains("AAB"))
    }

    @Test
    fun hostile_names_inside_the_zip_cannot_choose_where_a_bundle_goes() = runTest {
        val files = download(zipOf("../../evil.aab" to fakeAab()))
        assertEquals(File(tmp.root, "apks/b1").canonicalFile, files[0].parentFile.canonicalFile)
        assertEquals("evil.aab", files[0].name)
    }
}

class Phase6ArtifactStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun the_store_lists_apks_and_bundles_and_flags_the_bundles() {
        val store = ApkStore(File(tmp.root, "apks"))
        File(store.dirFor("b1"), "app.apk").apply { parentFile.mkdirs(); writeBytes(fakeApk()) }
        File(store.dirFor("b1"), "app.aab").writeBytes(fakeAab())
        File(store.dirFor("b1"), "notes.txt").writeText("x")
        val list = store.list("b1")
        assertEquals(listOf("app.aab", "app.apk"), list.map { it.fileName })
        assertTrue(list.first { it.fileName == "app.aab" }.isBundle)
        assertFalse(list.first { it.fileName == "app.apk" }.isBundle)
        assertEquals(listOf("b1"), store.buildIdsWithFiles())
        assertNull(store.find("b1", "../x.aab"))
        assertTrue(store.find("b1", "app.aab") != null)
    }

    @Test
    fun builds_without_saved_files_are_not_listed() {
        val store = ApkStore(File(tmp.root, "apks"))
        store.dirFor("empty").mkdirs()
        File(store.tempDir, "x.part").apply { parentFile.mkdirs(); writeText("x") }
        assertTrue(store.buildIdsWithFiles().isEmpty())
    }

    @Test
    fun copying_a_bundle_to_a_chosen_destination_is_byte_exact() {
        val store = ApkStore(File(tmp.root, "apks"))
        val bytes = fakeAab()
        File(store.dirFor("b1"), "app.aab").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val out = ByteArrayOutputStream()
        store.copyTo(store.list("b1").single(), out)
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun details_for_a_bundle_come_from_the_build_settings() {
        val d = ArtifactDetails.fromConfiguration(BuildConfiguration(BuildVariant.RELEASE_AAB, applicationId = "com.a.b", versionName = "3", versionCode = 30))
        assertEquals("com.a.b", d.packageName)
        assertEquals(30L, d.versionCode)
        assertFalse(d.fromFile)
        assertNull(ArtifactDetails.fromConfiguration(null).packageName)
    }
}

class Phase6ArtifactsViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val inspector = ArtifactInspector { _, isBundle, fallback ->
        if (isBundle) ArtifactDetails.fromConfiguration(fallback) else ArtifactDetails("com.real.pkg", "1.0", 1L, fromFile = true)
    }

    @Test
    fun the_manager_lists_saved_files_with_project_build_type_and_details() = runTest {
        val store = ApkStore(File(tmp.root, "apks"))
        File(store.dirFor("b1"), "app.aab").apply { parentFile.mkdirs(); writeBytes(fakeAab()) }
        File(store.dirFor("b2"), "app.apk").apply { parentFile.mkdirs(); writeBytes(fakeApk()) }
        val builds = InMemoryBuildStore()
        builds.saveBuild(
            BuildRecord(
                "b1", "Shop", BuildStatus.SUCCESS, 1L, 5, "s", isSample = false,
                configuration = BuildConfiguration(BuildVariant.RELEASE_AAB, applicationId = "com.shop.app", versionCode = 4),
            ),
        )
        val vm = ArtifactsViewModel(builds, store, inspector)
        val items = (vm.state.value as UiState.Success).data
        assertEquals(2, items.size)
        val aab = items.first { it.file.isBundle }
        assertEquals("Shop", aab.projectName)
        assertEquals("Release AAB", aab.buildLabel)
        assertEquals("com.shop.app", aab.details.packageName)
        val apk = items.first { !it.file.isBundle }
        assertEquals("Unknown project", apk.projectName)
        assertEquals("com.real.pkg", apk.details.packageName)
        assertTrue(apk.details.fromFile)
    }

    @Test
    fun delete_removes_the_file_and_refreshes_the_list() = runTest {
        val store = ApkStore(File(tmp.root, "apks"))
        File(store.dirFor("b1"), "app.apk").apply { parentFile.mkdirs(); writeBytes(fakeApk()) }
        val vm = ArtifactsViewModel(InMemoryBuildStore(), store, inspector)
        val item = (vm.state.value as UiState.Success).data.single()
        vm.delete(item)
        assertTrue((vm.state.value as UiState.Success).data.isEmpty())
    }

    @Test
    fun saving_to_a_destination_reports_success_and_failure() = runTest {
        val store = ApkStore(File(tmp.root, "apks"))
        File(store.dirFor("b1"), "app.apk").apply { parentFile.mkdirs(); writeBytes(fakeApk()) }
        val vm = ArtifactsViewModel(InMemoryBuildStore(), store, inspector)
        val item = (vm.state.value as UiState.Success).data.single()
        val sink = ByteArrayOutputStream()
        vm.export(item) { sink }
        assertEquals("Saved app.apk", vm.message.value)
        assertTrue(sink.size() > 0)
        vm.clearMessage()
        vm.export(item) { null }
        assertTrue(vm.message.value!!.startsWith("Could not save"))
    }
}
