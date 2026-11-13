package com.almus.appcreatorpro.workspace

import com.almus.appcreatorpro.domain.github.GitHubException
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.service.github.GitHubApiImpl
import com.almus.appcreatorpro.service.github.TokenProvider
import com.almus.appcreatorpro.service.transfer.GitHubRepoDownloader
import com.almus.appcreatorpro.testutil.FakeClock
import com.almus.appcreatorpro.testutil.FakeStreamTransport
import com.almus.appcreatorpro.testutil.FakeTransport
import com.almus.appcreatorpro.testutil.Reply
import com.almus.appcreatorpro.testutil.jsonResponse
import com.almus.appcreatorpro.transfer.text
import com.almus.appcreatorpro.transfer.zipBytes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PublicAccessTest {
    @get:Rule val tmp = TemporaryFolder()
    private val clock = FakeClock()
    private val transport = FakeTransport()

    private fun api(token: String?, anonymous: Boolean) = GitHubApiImpl(
        transport = transport, tokenProvider = { token }, now = clock::now, sleep = clock::sleep, allowAnonymousReads = anonymous,
    )

    private val repoJson = """{"id":7,"name":"hello","owner":{"login":"octocat"},"private":false,"default_branch":"main",
        "html_url":"https://github.com/octocat/hello","description":"Demo","language":"Kotlin","stargazers_count":42,
        "pushed_at":"2026-01-02T03:04:05Z","size":2048}"""

    @Test fun `public reads work without any token and send no Authorization header`() = runTest {
        transport.on("GET", "/repos/octocat/hello", jsonResponse(body = repoJson))
        val repo = api(token = null, anonymous = true).getRepository("octocat", "hello")
        assertEquals("octocat/hello", repo.fullName)
        assertEquals("Kotlin", repo.language)
        assertEquals(42, repo.stars)
        assertEquals(2048L, repo.sizeKb)
        assertEquals("2026-01-02T03:04:05Z", repo.updatedAtIso)
        assertNull(transport.requests.single().headers["Authorization"])
    }

    @Test fun `with a token the same client still sends it`() = runTest {
        transport.on("GET", "/repos/octocat/hello", jsonResponse(body = repoJson))
        api(token = "ghp_secret", anonymous = true).getRepository("octocat", "hello")
        assertEquals("Bearer ghp_secret", transport.requests.single().headers["Authorization"])
    }

    @Test fun `the default client and all writes still require a sign-in`() = runTest {
        try {
            api(token = null, anonymous = false).getRepository("octocat", "hello")
            fail("expected NOT_CONNECTED")
        } catch (e: GitHubException) {
            assertEquals(GitHubException.Kind.NOT_CONNECTED, e.kind)
        }
        try {
            api(token = null, anonymous = true).createBranch("octocat", "hello", "x", "sha")
            fail("expected NOT_CONNECTED")
        } catch (e: GitHubException) {
            assertEquals(GitHubException.Kind.NOT_CONNECTED, e.kind)
        }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun `search parses results and pages`() = runTest {
        transport.on("GET", "/search/repositories", jsonResponse(body = """{"total_count":100,"items":[$repoJson]}"""))
        val page = api(token = null, anonymous = true).searchRepositories("android compose", 1)
        assertEquals(1, page.items.size)
        assertEquals(2, page.nextPage)
        assertTrue(transport.requests.single().url.contains("q=android%20compose"))
        assertEquals(0, api(null, true).searchRepositories("   ", 1).items.size)
    }

    // ---- downloader
    private val zip = zipBytes("repo-abc/index.html" to text("<html/>"))
    private val signed = "https://codeload.github.com/o/r/legacy.zip/abc?token=SECRET"

    @Test fun `anonymous download of a public repository sends no token anywhere`() = runTest {
        val stream = FakeStreamTransport(Reply.redirect(signed), Reply.ok(zip))
        val target = File(tmp.root, "r.zip")
        GitHubRepoDownloader(stream, TokenProvider { null }).download("o", "r", "abc", target, allowAnonymous = true)
        assertTrue(target.length() > 0)
        assertEquals(2, stream.requests.size)
        assertFalse(stream.requests.any { it.headers.containsKey("authorization") })
    }

    @Test fun `anonymous download is refused unless asked for`() = runTest {
        val stream = FakeStreamTransport(Reply.ok(zip))
        try {
            GitHubRepoDownloader(stream, TokenProvider { null }).download("o", "r", "abc", File(tmp.root, "r.zip"))
            fail("expected AUTH")
        } catch (e: TransferException) {
            assertEquals(TransferException.Kind.AUTH, e.kind)
        }
        assertTrue(stream.requests.isEmpty())
    }
}
