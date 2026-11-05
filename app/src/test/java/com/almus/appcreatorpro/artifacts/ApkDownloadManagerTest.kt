package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.data.repository.InMemoryBuildStore
import com.almus.appcreatorpro.domain.model.ArtifactStatus
import com.almus.appcreatorpro.domain.model.BuildArtifactInfo
import com.almus.appcreatorpro.domain.model.BuildRecord
import com.almus.appcreatorpro.domain.model.BuildStatus
import com.almus.appcreatorpro.service.artifacts.ApkDownloadManager
import com.almus.appcreatorpro.service.artifacts.ApkStore
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException.Kind
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloader
import com.almus.appcreatorpro.service.artifacts.DownloadKey
import com.almus.appcreatorpro.service.artifacts.DownloadStart
import com.almus.appcreatorpro.service.artifacts.DownloadState
import com.almus.appcreatorpro.service.github.TokenProvider
import com.almus.appcreatorpro.testutil.FakeStreamTransport
import com.almus.appcreatorpro.testutil.Gate
import com.almus.appcreatorpro.testutil.Reply
import com.almus.appcreatorpro.testutil.artifactZip
import com.almus.appcreatorpro.testutil.fakeApk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException

class ApkDownloadManagerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signedUrl = "https://storage.example.net/blob?sig=x"
    private val transport = FakeStreamTransport()
    private val builds = InMemoryBuildStore()
    private var nowMillis = 1_000_000L
    private val artifact = BuildArtifactInfo(id = 42, name = "almus-apk-abc123def456", sizeBytes = 5000, expiresAtMillis = 9_000_000L)
    private val record = BuildRecord(
        id = "b1", projectName = "P", status = BuildStatus.SUCCESS, startedAtMillis = 1, durationSeconds = 60, summary = "s",
        isSample = false, projectId = "p1", repoFullName = "musty/builds", branch = "main", runId = 7, artifacts = listOf(artifact),
        artifactNames = listOf(artifact.name),
    )
    private val key = DownloadKey("b1", 42)

    private lateinit var store: ApkStore
    private lateinit var manager: ApkDownloadManager

    private fun setUp() {
        store = ApkStore(tmp.newFolder("apks"))
        val downloader = ArtifactDownloader(transport, TokenProvider { "ghu_abcdefghijklmnopqrstuvwxyz123456" })
        manager = ApkDownloadManager(downloader, store, builds, CoroutineScope(Dispatchers.Unconfined), now = { nowMillis })
    }

    private fun script(vararg r: Reply) = transport.reset(*r)

    @Test
    fun a_download_ends_in_Finished_with_the_saved_apk() = runTest {
        setUp()
        builds.saveBuild(record)
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip()))
        assertEquals(DownloadStart.Started, manager.start(record, artifact))
        val state = manager.states.value[key] as DownloadState.Finished
        assertEquals("app-debug.apk", state.apks.single().fileName)
        assertEquals(1, store.list("b1").size)
        assertFalse(manager.isActive(key))
    }

    @Test
    fun the_same_artifact_cannot_be_downloaded_twice_at_once() = runTest {
        setUp()
        val gate = Gate()
        script(Reply.redirect(signedUrl), Reply(200, mapOf("content-length" to artifactZip().size.toString()), { ByteArrayInputStream(artifactZip()) }, before = { gate.await() }))
        assertEquals(DownloadStart.Started, manager.start(record, artifact))
        assertTrue(manager.isActive(key))
        assertTrue(manager.states.value[key] is DownloadState.Active)
        assertEquals(DownloadStart.AlreadyRunning, manager.start(record, artifact))
        assertEquals("only one set of requests was made", 2, transport.requests.size)
        gate.open()
        assertTrue(manager.states.value[key] is DownloadState.Finished)
        assertFalse(manager.isActive(key))
    }

    @Test
    fun cancel_stops_the_download_removes_partial_files_and_allows_starting_again() = runTest {
        setUp()
        val gate = Gate()
        script(Reply.redirect(signedUrl), Reply(200, emptyMap(), { ByteArrayInputStream(artifactZip()) }, before = { gate.await() }))
        manager.start(record, artifact)
        manager.cancel(key)
        assertEquals(DownloadState.Cancelled, manager.states.value[key])
        assertFalse(manager.isActive(key))
        assertTrue(store.tempDir.listFiles().orEmpty().isEmpty())
        assertTrue(store.list("b1").isEmpty())
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip()))
        assertEquals(DownloadStart.Started, manager.start(record, artifact))
        assertTrue(manager.states.value[key] is DownloadState.Finished)
    }

    @Test
    fun a_failed_download_can_be_retried_and_the_failure_says_whether_retrying_helps() = runTest {
        setUp()
        val zip = artifactZip()
        script(Reply.redirect(signedUrl), Reply(0, throws = IOException("offline")))
        manager.start(record, artifact)
        val failed = manager.states.value[key] as DownloadState.Failed
        assertEquals(Kind.NETWORK, failed.kind)
        assertTrue(failed.retryable)
        script(Reply.redirect(signedUrl), Reply.ok(zip))
        assertEquals(DownloadStart.Started, manager.start(record, artifact))
        assertTrue(manager.states.value[key] is DownloadState.Finished)
    }

    @Test
    fun an_artifact_known_to_be_expired_is_not_requested_at_all() = runTest {
        setUp()
        nowMillis = 9_000_001L
        val result = manager.start(record, artifact)
        assertTrue(result is DownloadStart.NotAvailable)
        assertTrue((result as DownloadStart.NotAvailable).reason.contains("expired"))
        assertTrue(transport.requests.isEmpty())
        assertNull(manager.states.value[key])
    }

    @Test
    fun deleted_or_expired_status_blocks_the_download() = runTest {
        setUp()
        assertTrue(manager.start(record, artifact.copy(status = ArtifactStatus.EXPIRED)) is DownloadStart.NotAvailable)
        assertTrue(manager.start(record, artifact.copy(status = ArtifactStatus.DELETED)) is DownloadStart.NotAvailable)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun github_saying_410_marks_the_artifact_expired_in_the_history() = runTest {
        setUp()
        builds.saveBuild(record)
        script(Reply.status(410))
        manager.start(record, artifact)
        assertEquals(Kind.EXPIRED, (manager.states.value[key] as DownloadState.Failed).kind)
        val saved = builds.getBuild("b1")!!
        assertEquals(ArtifactStatus.EXPIRED, saved.artifacts.single().status)
        assertTrue(saved.artifactNames.isEmpty())
    }

    @Test
    fun github_saying_404_marks_the_artifact_deleted_in_the_history() = runTest {
        setUp()
        builds.saveBuild(record)
        script(Reply.status(404))
        manager.start(record, artifact)
        assertEquals(ArtifactStatus.DELETED, builds.getBuild("b1")!!.artifacts.single().status)
    }

    @Test
    fun an_expired_sign_in_is_reported_and_is_not_retryable_until_reconnecting() = runTest {
        setUp()
        script(Reply.status(401))
        manager.start(record, artifact)
        val failed = manager.states.value[key] as DownloadState.Failed
        assertEquals(Kind.TOKEN_REJECTED, failed.kind)
        assertFalse(failed.retryable)
    }

    @Test
    fun a_record_without_repository_cannot_download() = runTest {
        setUp()
        assertTrue(manager.start(record.copy(repoFullName = null), artifact) is DownloadStart.NotAvailable)
    }

    @Test
    fun dismiss_clears_a_finished_message_but_not_a_running_download() = runTest {
        setUp()
        script(Reply.status(500))
        manager.start(record, artifact)
        manager.dismiss(key)
        assertNull(manager.states.value[key])
        val gate = Gate()
        script(Reply.redirect(signedUrl), Reply(200, emptyMap(), { ByteArrayInputStream(artifactZip()) }, before = { gate.await() }))
        manager.start(record, artifact)
        manager.dismiss(key)
        assertTrue(manager.states.value[key] is DownloadState.Active)
        gate.open()
    }

    @Test
    fun downloads_of_different_builds_do_not_interfere() = runTest {
        setUp()
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip(apk = fakeApk("one"))), Reply.redirect(signedUrl), Reply.ok(artifactZip(apk = fakeApk("two"))))
        manager.start(record, artifact)
        manager.start(record.copy(id = "b2"), artifact)
        assertArrayEquals(fakeApk("one"), store.list("b1").single().file.readBytes())
        assertArrayEquals(fakeApk("two"), store.list("b2").single().file.readBytes())
    }
}

class ApkStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun lists_finds_deletes_and_copies_apks() {
        val store = ApkStore(tmp.newFolder("apks"))
        store.dirFor("b1").mkdirs()
        java.io.File(store.dirFor("b1"), "app-debug.apk").writeBytes(fakeApk())
        java.io.File(store.dirFor("b1"), "notes.txt").writeText("x")
        java.io.File(store.dirFor("b1"), "empty.apk").writeBytes(ByteArray(0))
        assertEquals(listOf("app-debug.apk"), store.list("b1").map { it.fileName })
        assertTrue(store.list("other").isEmpty())
        val apk = store.find("b1", "app-debug.apk")!!
        val out = java.io.ByteArrayOutputStream()
        assertEquals(apk.sizeBytes, store.copyTo(apk, out))
        assertArrayEquals(fakeApk(), out.toByteArray())
        assertTrue(store.delete("b1", "app-debug.apk"))
        assertNull(store.find("b1", "app-debug.apk"))
    }

    @Test
    fun build_ids_and_file_names_cannot_escape_the_store() {
        val store = ApkStore(tmp.newFolder("apks"))
        listOf("../x", "a/b", "", ".hidden", "a b").forEach {
            try {
                store.dirFor(it)
                org.junit.Assert.fail("accepted $it")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
        assertNull(store.find("b1", "../../secret.apk"))
        assertFalse(store.delete("b1", "../x.apk"))
        assertFalse(ApkStore.validFileName("a..b.apk"))
        assertTrue(ApkStore.validFileName("app-debug_2.apk"))
    }

    @Test
    fun cleanTemp_removes_leftovers_only_from_the_temp_folder() {
        val store = ApkStore(tmp.newFolder("apks"))
        store.tempDir.mkdirs()
        java.io.File(store.tempDir, "artifact-1.zip.part").writeText("partial")
        store.dirFor("b1").mkdirs()
        java.io.File(store.dirFor("b1"), "app.apk").writeBytes(fakeApk())
        store.cleanTemp()
        assertTrue(store.tempDir.listFiles().orEmpty().isEmpty())
        assertEquals(1, store.list("b1").size)
    }
}
