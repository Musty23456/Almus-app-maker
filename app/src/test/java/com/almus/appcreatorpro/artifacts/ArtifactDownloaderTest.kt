package com.almus.appcreatorpro.artifacts

import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException.Kind
import com.almus.appcreatorpro.service.artifacts.ArtifactDownloader
import com.almus.appcreatorpro.service.artifacts.DownloadLimits
import com.almus.appcreatorpro.service.artifacts.DownloadProgress
import com.almus.appcreatorpro.service.github.TokenProvider
import com.almus.appcreatorpro.testutil.BrokenInput
import com.almus.appcreatorpro.testutil.FakeStreamTransport
import com.almus.appcreatorpro.testutil.Reply
import com.almus.appcreatorpro.testutil.artifactZip
import com.almus.appcreatorpro.testutil.cancelCaller
import com.almus.appcreatorpro.testutil.fakeApk
import com.almus.appcreatorpro.testutil.zipOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException

class ArtifactDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val signedUrl = "https://storage.example.net/blob/abc?sig=SECRETSIGNATURE&se=2026"
    private val transport = FakeStreamTransport()
    private var token: String? = "ghu_TOKENTOKENTOKENTOKENTOKEN123456"
    private var unauthorizedCalls = 0

    private lateinit var dest: File
    private lateinit var temp: File

    private fun downloader(limits: DownloadLimits = DownloadLimits()) = ArtifactDownloader(
        transport = transport,
        tokenProvider = TokenProvider { token },
        onUnauthorized = { unauthorizedCalls++ },
        limits = limits,
    )

    private fun dirs() {
        dest = File(tmp.root, "apks/b1")
        temp = File(tmp.root, "apks/.tmp")
    }

    private suspend fun download(d: ArtifactDownloader = downloader(), progress: (DownloadProgress) -> Unit = {}): List<File> {
        dirs()
        return d.download("musty", "builds", 42L, dest, temp, progress)
    }

    private suspend fun failure(d: ArtifactDownloader = downloader()): ArtifactDownloadException {
        try {
            download(d)
        } catch (e: ArtifactDownloadException) {
            return e
        }
        fail("expected the download to fail")
        throw IllegalStateException()
    }

    private fun script(vararg replies: Reply) = transport.reset(*replies)

    private fun leftovers() = temp.listFiles().orEmpty().toList()

    // ------------------------------------------------------------------ success

    @Test
    fun successful_download_saves_a_verified_apk_and_leaves_no_temp_files() = runTest {
        val apk = fakeApk("hello")
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip("app-debug.apk", apk)))
        val files = download()
        assertEquals(1, files.size)
        assertEquals("app-debug.apk", files[0].name)
        assertArrayEquals(apk, files[0].readBytes())
        assertEquals(dest.canonicalFile, files[0].parentFile.canonicalFile)
        assertTrue("temp files must be cleaned up", leftovers().isEmpty())
    }

    @Test
    fun token_goes_only_to_the_github_api_never_to_the_storage_host() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip()))
        download()
        assertEquals(2, transport.requests.size)
        val first = transport.requests[0]
        assertTrue(first.url.startsWith("https://api.github.com/repos/musty/builds/actions/artifacts/42/zip"))
        assertEquals("Bearer $token", first.headers["authorization"])
        val second = transport.requests[1]
        assertEquals(signedUrl, second.url)
        assertNull("the token must not be sent to the storage host", second.headers["authorization"])
        assertFalse(second.headers.values.any { it.contains("TOKENTOKEN") })
    }

    @Test
    fun progress_is_reported_from_connecting_to_saving_with_a_total() = runTest {
        val zip = artifactZip(apk = fakeApk("x".repeat(200_000)))
        script(Reply.redirect(signedUrl), Reply.ok(zip))
        val seen = mutableListOf<DownloadProgress>()
        download(progress = { seen += it })
        assertEquals(DownloadProgress.Phase.CONNECTING, seen.first().phase)
        val downloading = seen.filter { it.phase == DownloadProgress.Phase.DOWNLOADING }
        assertTrue(downloading.isNotEmpty())
        assertEquals(zip.size.toLong(), downloading.last().bytesDone)
        assertEquals(zip.size.toLong(), downloading.last().totalBytes)
        assertEquals(1f, downloading.last().fraction!!, 0.0001f)
        assertEquals(DownloadProgress.Phase.SAVING, seen.last().phase)
        assertTrue(seen.indexOfFirst { it.phase == DownloadProgress.Phase.VERIFYING } > seen.indexOfFirst { it.phase == DownloadProgress.Phase.DOWNLOADING })
    }

    @Test
    fun unknown_size_gives_no_percentage_but_still_downloads() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip(), announce = false))
        val seen = mutableListOf<DownloadProgress>()
        val files = download(progress = { seen += it })
        assertEquals(1, files.size)
        assertNull(seen.first { it.phase == DownloadProgress.Phase.DOWNLOADING }.fraction)
    }

    @Test
    fun a_second_download_replaces_the_earlier_copy() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip(apk = fakeApk("v1"))))
        download()
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip(apk = fakeApk("v2"))))
        val files = download()
        assertEquals(1, dest.listFiles()!!.size)
        assertArrayEquals(fakeApk("v2"), files[0].readBytes())
    }

    @Test
    fun several_apks_are_all_saved_with_unique_safe_names() = runTest {
        val zip = zipOf(
            "app/build/outputs/apk/debug/app-debug.apk" to fakeApk("a"),
            "other/build/outputs/apk/debug/app-debug.apk" to fakeApk("b"),
            "notes.txt" to "ignore me".toByteArray(),
        )
        script(Reply.redirect(signedUrl), Reply.ok(zip))
        val names = download().map { it.name }.sorted()
        assertEquals(listOf("app-debug.apk", "app-debug_2.apk"), names)
    }

    @Test
    fun a_relative_redirect_is_resolved_against_github_and_must_be_https() = runTest {
        script(Reply.redirect("/other/path?x=1"), Reply.ok(artifactZip()))
        download()
        assertEquals("https://api.github.com/other/path?x=1", transport.requests[1].url)
    }

    @Test
    fun a_second_redirect_hop_is_followed_without_the_token() = runTest {
        script(Reply.redirect(signedUrl), Reply.redirect("https://cdn.example.net/final"), Reply.ok(artifactZip()))
        download()
        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests.drop(1).all { it.headers["authorization"] == null })
    }

    @Test
    fun endless_redirects_are_stopped() = runTest {
        script(Reply.redirect(signedUrl), Reply.redirect(signedUrl))
        assertEquals(Kind.BAD_REDIRECT, failure().kind)
    }

    @Test
    fun a_direct_200_from_the_api_is_accepted_too() = runTest {
        script(Reply.ok(artifactZip()))
        assertEquals(1, download().size)
        assertEquals(1, transport.requests.size)
    }

    // ------------------------------------------------------------------ unsafe or missing redirects

    @Test
    fun an_http_redirect_is_refused_and_not_followed() = runTest {
        script(Reply.redirect("http://storage.example.net/blob"), Reply.ok(artifactZip()))
        assertEquals(Kind.BAD_REDIRECT, failure().kind)
        assertEquals("nothing may be requested over plain http", 1, transport.requests.size)
    }

    @Test
    fun a_redirect_without_location_is_refused() = runTest {
        script(Reply.redirect(null))
        assertEquals(Kind.BAD_REDIRECT, failure().kind)
    }

    @Test
    fun a_redirect_with_embedded_credentials_is_refused() = runTest {
        script(Reply.redirect("https://user:pw@storage.example.net/blob"))
        assertEquals(Kind.BAD_REDIRECT, failure().kind)
    }

    @Test
    fun error_messages_never_contain_the_token_or_the_signed_url() = runTest {
        val messages = mutableListOf<String>()
        for (replies in listOf(
            arrayOf(Reply.redirect("http://x.example/blob?sig=SECRETSIGNATURE")),
            arrayOf(Reply.redirect(signedUrl), Reply.status(403)),
            arrayOf(Reply.redirect(signedUrl), Reply.ok(ByteArray(10) { 1 })),
            arrayOf(Reply.status(401)),
        )) {
            script(*replies)
            messages += failure().message.orEmpty()
        }
        messages.forEach {
            assertFalse(it, it.contains("SECRETSIGNATURE"))
            assertFalse(it, it.contains("TOKENTOKEN"))
            assertFalse(it, it.contains("storage.example"))
        }
    }

    // ------------------------------------------------------------------ GitHub's answers

    @Test
    fun not_signed_in_fails_before_any_request() = runTest {
        token = null
        val e = failure()
        assertEquals(Kind.NOT_CONNECTED, e.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun a_rejected_token_is_reported_and_the_app_is_told_to_clear_it() = runTest {
        script(Reply.status(401))
        val e = failure()
        assertEquals(Kind.TOKEN_REJECTED, e.kind)
        assertFalse(e.retryable)
        assertEquals(1, unauthorizedCalls)
    }

    @Test
    fun expired_artifact_is_gone_410() = runTest {
        script(Reply.status(410))
        val e = failure()
        assertEquals(Kind.EXPIRED, e.kind)
        assertTrue(e.message!!.contains("expired"))
        assertFalse(e.retryable)
    }

    @Test
    fun deleted_artifact_is_404() = runTest {
        script(Reply.status(404))
        val e = failure()
        assertEquals(Kind.NOT_FOUND, e.kind)
        assertFalse(e.retryable)
    }

    @Test
    fun rate_limit_and_forbidden_are_told_apart() = runTest {
        script(Reply.status(403, mapOf("x-ratelimit-remaining" to "0")))
        assertEquals(Kind.RATE_LIMITED, failure().kind)
        script(Reply.status(429))
        assertEquals(Kind.RATE_LIMITED, failure().kind)
        script(Reply.status(403))
        val forbidden = failure()
        assertEquals(Kind.FORBIDDEN, forbidden.kind)
        assertFalse(forbidden.retryable)
    }

    @Test
    fun server_errors_are_retryable() = runTest {
        script(Reply.status(503))
        val e = failure()
        assertEquals(Kind.SERVER, e.kind)
        assertTrue(e.retryable)
        script(Reply.status(418))
        assertEquals(Kind.UNKNOWN, failure().kind)
    }

    @Test
    fun an_expired_temporary_link_is_retryable() = runTest {
        script(Reply.redirect(signedUrl), Reply.status(403))
        val e = failure()
        assertEquals(Kind.LINK_REJECTED, e.kind)
        assertTrue(e.retryable)
        script(Reply.redirect(signedUrl), Reply.status(500))
        assertEquals(Kind.SERVER, failure().kind)
    }

    @Test
    fun connection_problems_are_network_errors() = runTest {
        script(Reply(0, throws = IOException("no route")))
        assertEquals(Kind.NETWORK, failure().kind)
        script(Reply(0, throws = SocketTimeoutException("slow")))
        assertEquals(Kind.NETWORK, failure().kind)
        script(Reply.redirect(signedUrl), Reply(0, throws = IOException("no route")))
        assertEquals(Kind.NETWORK, failure().kind)
    }

    // ------------------------------------------------------------------ interrupted and damaged downloads

    @Test
    fun a_connection_that_drops_mid_download_is_interrupted_and_leaves_nothing_behind() = runTest {
        val zip = artifactZip(apk = fakeApk("y".repeat(300_000)))
        script(Reply.redirect(signedUrl), Reply(200, mapOf("content-length" to zip.size.toString()), { BrokenInput(zip, zip.size / 2) }))
        val e = failure()
        assertEquals(Kind.INTERRUPTED, e.kind)
        assertTrue(e.retryable)
        assertTrue(leftovers().isEmpty())
        assertTrue(dest.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun an_interrupted_download_keeps_the_apk_saved_earlier() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip(apk = fakeApk("good"))))
        download()
        val zip = artifactZip(apk = fakeApk(String(CharArray(50_000) { ('a'.code + (it * 7919) % 26).toChar() })))
        script(Reply.redirect(signedUrl), Reply(200, mapOf("content-length" to zip.size.toString()), { BrokenInput(zip, 200) }))
        assertEquals(Kind.INTERRUPTED, failure().kind)
        assertArrayEquals(fakeApk("good"), File(dest, "app-debug.apk").readBytes())
    }

    @Test
    fun fewer_bytes_than_announced_is_a_truncated_download() = runTest {
        val zip = artifactZip()
        script(Reply.redirect(signedUrl), Reply(200, mapOf("content-length" to (zip.size + 500).toString()), { ByteArrayInputStream(zip) }))
        val e = failure()
        assertEquals(Kind.INTERRUPTED, e.kind)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun retrying_after_an_interruption_succeeds() = runTest {
        val zip = artifactZip()
        script(Reply.redirect(signedUrl), Reply(200, mapOf("content-length" to zip.size.toString()), { BrokenInput(zip, 10) }))
        assertEquals(Kind.INTERRUPTED, failure().kind)
        script(Reply.redirect(signedUrl), Reply.ok(zip))
        assertEquals(1, download().size)
    }

    @Test
    fun an_empty_body_is_rejected() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(ByteArray(0)))
        assertEquals(Kind.CORRUPT, failure().kind)
    }

    @Test
    fun something_that_is_not_a_zip_is_rejected() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok("<html>Please sign in</html>".toByteArray()))
        val e = failure()
        assertEquals(Kind.CORRUPT, e.kind)
        assertTrue(dest.listFiles().orEmpty().isEmpty())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun a_zip_without_an_apk_is_reported_honestly() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(zipOf("build.log" to "text".toByteArray())))
        val e = failure()
        assertEquals(Kind.NO_APK, e.kind)
        assertFalse(e.retryable)
    }

    @Test
    fun a_file_named_apk_that_is_not_an_apk_is_rejected_and_nothing_is_saved() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(zipOf("app-debug.apk" to "just text".toByteArray())))
        assertEquals(Kind.CORRUPT, failure().kind)
        assertTrue(dest.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun a_zip_with_a_bad_checksum_is_rejected() = runTest {
        val zip = artifactZip(apk = fakeApk("PAYLOAD-PAYLOAD-PAYLOAD"))
        // Flip a byte inside the stored data of the entry so the CRC no longer matches (entries are DEFLATED, so corrupt the compressed bytes).
        val corrupted = zip.copyOf()
        val at = corrupted.indexOfFirst { it == 'P'.code.toByte() } // first byte of the "PK" header
        assertTrue(at >= 0)
        corrupted[corrupted.size / 3] = (corrupted[corrupted.size / 3].toInt() xor 0x55).toByte()
        script(Reply.redirect(signedUrl), Reply.ok(corrupted))
        val e = failure()
        assertEquals(Kind.CORRUPT, e.kind)
        assertTrue(dest.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun path_tricks_in_zip_entry_names_cannot_write_outside_the_apk_folder() = runTest {
        val zip = zipOf("../../evil.apk" to fakeApk("e"), "/abs/olute.apk" to fakeApk("f"))
        script(Reply.redirect(signedUrl), Reply.ok(zip))
        val files = download()
        files.forEach { assertEquals(dest.canonicalFile, it.parentFile.canonicalFile) }
        assertEquals(setOf("evil.apk", "olute.apk"), files.map { it.name }.toSet())
        assertFalse(File(tmp.root, "evil.apk").exists())
        assertFalse(File(tmp.root, "apks/evil.apk").exists())
    }

    @Test
    fun unsafe_characters_in_entry_names_are_replaced() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(zipOf("my app (1);rm -rf.apk" to fakeApk())))
        val name = download().single().name
        assertTrue(name, Regex("[A-Za-z0-9._-]+\\.apk").matches(name))
    }

    // ------------------------------------------------------------------ size limits

    @Test
    fun an_announced_size_over_the_limit_is_refused_before_downloading() = runTest {
        script(Reply.redirect(signedUrl), Reply(200, mapOf("content-length" to "999999999"), { ByteArrayInputStream(ByteArray(0)) }))
        val e = failure(downloader(DownloadLimits(maxArtifactBytes = 1_000_000)))
        assertEquals(Kind.TOO_LARGE, e.kind)
        assertFalse(e.retryable)
    }

    @Test
    fun a_server_that_lies_about_the_size_is_stopped_at_the_limit() = runTest {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        script(Reply.redirect(signedUrl), Reply.ok(big, announce = false))
        assertEquals(Kind.TOO_LARGE, failure(downloader(DownloadLimits(maxArtifactBytes = 100_000))).kind)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun a_zip_bomb_is_stopped_by_counting_the_bytes_actually_extracted() = runTest {
        // 2 MB of zeros compresses to a few KB: tiny on the wire, huge once unzipped.
        val bomb = artifactZip(apk = ByteArray(2_000_000))
        assertTrue(bomb.size < 50_000)
        script(Reply.redirect(signedUrl), Reply.ok(bomb))
        val e = failure(downloader(DownloadLimits(maxApkBytes = 100_000)))
        assertEquals(Kind.TOO_LARGE, e.kind)
        assertTrue(dest.listFiles().orEmpty().isEmpty())
        assertTrue(leftovers().isEmpty())
    }

    // ------------------------------------------------------------------ storage and cancellation

    @Test
    fun unwritable_storage_is_reported_as_a_storage_problem() = runTest {
        script(Reply.redirect(signedUrl), Reply.ok(artifactZip()))
        dest = File(tmp.root, "apks/b1")
        val blocker = File(tmp.root, "blocker").also { it.writeText("a file, not a folder") }
        val e = try {
            downloader().download("musty", "builds", 42L, File(blocker, "sub"), File(tmp.root, "t"))
            null
        } catch (e: ArtifactDownloadException) {
            e
        }
        assertNotNull(e)
        assertEquals(Kind.STORAGE, e!!.kind)
    }

    @Test
    fun cancelling_stops_the_download_and_removes_partial_data() = runTest {
        script(Reply.redirect(signedUrl), Reply(200, mapOf("content-length" to "5000"), { ByteArrayInputStream(artifactZip()) }, before = cancelCaller()))
        val job = launch { download() }
        job.join()
        assertTrue("the download coroutine must end cancelled", job.isCancelled)
        assertTrue(leftovers().isEmpty())
        assertTrue(dest.listFiles().orEmpty().isEmpty())
    }
}
