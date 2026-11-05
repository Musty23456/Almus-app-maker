package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.service.github.TokenProvider
import com.almus.appcreatorpro.service.transfer.GitHubRepoDownloader
import com.almus.appcreatorpro.testutil.BrokenInput
import com.almus.appcreatorpro.testutil.FakeStreamTransport
import com.almus.appcreatorpro.testutil.Reply
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
import java.io.File
import java.io.IOException

class GitHubRepoDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val zip = zipBytes("repo-abc/index.html" to text("<html/>"))
    private val signed = "https://codeload.github.com/owner/repo/legacy.zip/refs/heads/main?token=SECRET"

    private fun downloader(transport: FakeStreamTransport, token: String? = "ghp_secret", onUnauthorized: () -> Unit = {}, maxBytes: Long = 1_000_000L) =
        GitHubRepoDownloader(transport, TokenProvider { token }, onUnauthorized, maxBytes = maxBytes)

    private suspend fun failure(d: GitHubRepoDownloader, target: File, ref: String = "main"): TransferException {
        try {
            d.download("owner", "repo", ref, target)
            fail("expected TransferException")
        } catch (e: TransferException) {
            assertFalse("partial file must be removed", target.exists())
            return e
        }
        throw IllegalStateException()
    }

    @Test
    fun downloads_via_redirect_and_the_token_never_reaches_the_storage_host() = runTest {
        val transport = FakeStreamTransport(Reply.redirect(signed), Reply.ok(zip))
        val target = File(tmp.root, "repo.zip")
        val progress = ArrayList<TransferProgress>()
        downloader(transport).download("owner", "repo", "main", target) { progress += it }

        assertArrayEquals(zip, target.readBytes())
        assertEquals(2, transport.requests.size)
        val api = transport.requests[0]
        assertEquals("https://api.github.com/repos/owner/repo/zipball/main", api.url)
        assertEquals("Bearer ghp_secret", api.headers["authorization"])
        assertNull(transport.requests[1].headers["authorization"])
        assertEquals(signed, transport.requests[1].url)
        assertTrue(progress.any { it.phase == TransferProgress.Phase.DOWNLOADING })
    }

    @Test
    fun branch_names_with_slashes_and_odd_characters_are_encoded_per_segment() = runTest {
        val transport = FakeStreamTransport(Reply.redirect(signed), Reply.ok(zip))
        downloader(transport).download("o w", "repo", "feature/a b#1", File(tmp.root, "x.zip"))
        assertEquals("https://api.github.com/repos/o%20w/repo/zipball/feature/a%20b%231", transport.requests[0].url)
    }

    @Test
    fun not_signed_in_fails_before_any_request() = runTest {
        val transport = FakeStreamTransport(Reply.ok(zip))
        val e = failure(downloader(transport, token = null), File(tmp.root, "a.zip"))
        assertEquals(Kind.AUTH, e.kind)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun http_errors_are_translated_and_401_clears_the_sign_in() = runTest {
        var rejected = 0
        val e401 = failure(downloader(FakeStreamTransport(Reply.status(401)), onUnauthorized = { rejected++ }), File(tmp.root, "a.zip"))
        assertEquals(Kind.AUTH, e401.kind)
        assertEquals(1, rejected)
        assertEquals(Kind.NOT_FOUND, failure(downloader(FakeStreamTransport(Reply.status(404))), File(tmp.root, "b.zip")).kind)
        assertEquals(Kind.RATE_LIMITED, failure(downloader(FakeStreamTransport(Reply.status(403, mapOf("x-ratelimit-remaining" to "0")))), File(tmp.root, "c.zip")).kind)
        assertEquals(Kind.RATE_LIMITED, failure(downloader(FakeStreamTransport(Reply.status(429))), File(tmp.root, "d.zip")).kind)
        assertEquals(Kind.AUTH, failure(downloader(FakeStreamTransport(Reply.status(403))), File(tmp.root, "e.zip")).kind)
        assertEquals(Kind.SERVER, failure(downloader(FakeStreamTransport(Reply.status(502))), File(tmp.root, "f.zip")).kind)
    }

    @Test
    fun insecure_or_missing_redirects_are_refused_and_never_echoed() = runTest {
        val http = failure(downloader(FakeStreamTransport(Reply.redirect("http://evil.example/x.zip"))), File(tmp.root, "a.zip"))
        assertEquals(Kind.SERVER, http.kind)
        assertFalse(http.message!!.contains("evil"))
        assertEquals(Kind.SERVER, failure(downloader(FakeStreamTransport(Reply.redirect(null))), File(tmp.root, "b.zip")).kind)
        assertEquals(Kind.SERVER, failure(downloader(FakeStreamTransport(Reply.redirect("https://user:pw@host.example/x"))), File(tmp.root, "c.zip")).kind)
    }

    @Test
    fun an_expired_signed_link_is_reported_without_the_url() = runTest {
        val e = failure(downloader(FakeStreamTransport(Reply.redirect(signed), Reply.status(403))), File(tmp.root, "a.zip"))
        assertFalse(e.message!!.contains("SECRET"))
    }

    @Test
    fun too_many_redirects_stop() = runTest {
        val loop = FakeStreamTransport(Reply.redirect(signed))
        assertEquals(Kind.SERVER, failure(downloader(loop), File(tmp.root, "a.zip")).kind)
    }

    @Test
    fun size_limit_applies_to_announced_and_real_size() = runTest {
        val announced = failure(downloader(FakeStreamTransport(Reply.redirect(signed), Reply.ok(zip)), maxBytes = 10), File(tmp.root, "a.zip"))
        assertEquals(Kind.TOO_LARGE, announced.kind)
        val silent = failure(downloader(FakeStreamTransport(Reply.redirect(signed), Reply.ok(zip, announce = false)), maxBytes = 10), File(tmp.root, "b.zip"))
        assertEquals(Kind.TOO_LARGE, silent.kind)
    }

    @Test
    fun dropped_connection_and_empty_body_are_reported() = runTest {
        val dropped = Reply(200, mapOf("content-length" to zip.size.toString()), { BrokenInput(zip, failAfter = 10) })
        assertEquals(Kind.NETWORK, failure(downloader(FakeStreamTransport(Reply.redirect(signed), dropped)), File(tmp.root, "a.zip")).kind)
        assertEquals(Kind.SERVER, failure(downloader(FakeStreamTransport(Reply.redirect(signed), Reply.ok(ByteArray(0)))), File(tmp.root, "b.zip")).kind)
        val offline = Reply(0, throws = IOException("no route"))
        assertEquals(Kind.NETWORK, failure(downloader(FakeStreamTransport(offline)), File(tmp.root, "c.zip")).kind)
    }

    @Test
    fun short_download_is_detected() = runTest {
        val lying = Reply(200, mapOf("content-length" to (zip.size + 50).toString()), { zip.inputStream() })
        assertEquals(Kind.NETWORK, failure(downloader(FakeStreamTransport(Reply.redirect(signed), lying)), File(tmp.root, "a.zip")).kind)
    }
}
