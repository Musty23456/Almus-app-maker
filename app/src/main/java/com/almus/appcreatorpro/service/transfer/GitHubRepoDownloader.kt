package com.almus.appcreatorpro.service.transfer

import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import com.almus.appcreatorpro.service.artifacts.StreamResponse
import com.almus.appcreatorpro.service.artifacts.StreamTransport
import com.almus.appcreatorpro.service.github.TokenProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder

/**
 * Downloads the source code of one repository ref (branch name or commit sha) as a ZIP, using the app's existing
 * GitHub sign-in.
 *
 * Token handling follows the artifact downloader: `GET /repos/{owner}/{repo}/zipball/{ref}` answers with a redirect
 * to a short-lived pre-signed storage URL. The Authorization header goes ONLY to api.github.com; the redirect is
 * read by hand and followed WITHOUT the token, only over https. URLs are never logged or put in messages.
 *
 * The file is written to [target] (progress reported, size limit enforced on the bytes actually received). On any
 * failure the partial file is deleted.
 */
class GitHubRepoDownloader(
    private val transport: StreamTransport,
    private val tokenProvider: TokenProvider,
    private val onUnauthorized: () -> Unit = {},
    private val baseUrl: String = "https://api.github.com",
    private val maxBytes: Long = 300L * 1024 * 1024,
    private val maxRedirects: Int = 3,
    private val progressStepBytes: Long = 64L * 1024,
) {

    suspend fun download(
        owner: String,
        repo: String,
        ref: String,
        target: File,
        onProgress: (TransferProgress) -> Unit = {},
        /**
         * Phase 28: true lets a PUBLIC repository be downloaded without a sign-in (the request then carries no
         * Authorization header at all). Default false: every existing caller still needs a sign-in.
         */
        allowAnonymous: Boolean = false,
    ) {
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
        if (token == null && !allowAnonymous) {
            throw TransferException(Kind.AUTH, "You are not signed in to GitHub. Connect GitHub first.")
        }
        try {
            fetch(owner, repo, ref, token, target, onProgress)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private suspend fun fetch(owner: String, repo: String, ref: String, token: String?, target: File, onProgress: (TransferProgress) -> Unit) {
        val firstUrl = "$baseUrl/repos/${enc(owner)}/${enc(repo)}/zipball/${encPath(ref)}"
        var next: String? = null
        var done = false
        onProgress(TransferProgress(TransferProgress.Phase.DOWNLOADING))
        network {
            transport.get(firstUrl, apiHeaders(token)) { r ->
                when {
                    r.code in REDIRECTS -> next = checkedRedirect(r.header("location"), firstUrl)
                    r.code == 200 -> {
                        copyBody(r, target, onProgress)
                        done = true
                    }
                    else -> throw apiError(r)
                }
                Unit
            }
        }
        var hops = 0
        while (!done) {
            val url = next ?: throw TransferException(Kind.SERVER, "GitHub did not say where to download the repository from.")
            next = null
            network {
                // No Authorization header here: this host is not GitHub's API.
                transport.get(url, mapOf("User-Agent" to USER_AGENT, "Accept" to "application/octet-stream")) { r ->
                    when {
                        r.code == 200 -> {
                            copyBody(r, target, onProgress)
                            done = true
                        }
                        r.code in REDIRECTS -> next = checkedRedirect(r.header("location"), url)
                        r.code in 500..599 -> throw TransferException(Kind.SERVER, "GitHub's download server is having problems (HTTP ${r.code}). Try again in a moment.")
                        else -> throw TransferException(Kind.NETWORK, "GitHub's temporary download link was refused or has expired. Try again.")
                    }
                    Unit
                }
            }
            if (!done && ++hops > maxRedirects) {
                throw TransferException(Kind.SERVER, "The download was redirected too many times.")
            }
        }
    }

    private suspend fun copyBody(response: StreamResponse, target: File, onProgress: (TransferProgress) -> Unit) {
        val declared = response.contentLength
        if (declared != null && declared > maxBytes) {
            throw TransferException(Kind.TOO_LARGE, "The repository is ${declared / (1024 * 1024)} MB, more than the ${maxBytes / (1024 * 1024)} MB limit.")
        }
        target.parentFile?.mkdirs()
        val output = try {
            FileOutputStream(target)
        } catch (e: IOException) {
            throw TransferException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
        }
        var total = 0L
        output.use { out ->
            val buffer = ByteArray(BUFFER)
            var lastReported = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = try {
                    response.body.read(buffer)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw TransferException(Kind.NETWORK, "The connection was lost during the download.", e)
                }
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    throw TransferException(Kind.TOO_LARGE, "The repository is larger than the ${maxBytes / (1024 * 1024)} MB limit and the download was stopped.")
                }
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw TransferException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
                }
                if (total - lastReported >= progressStepBytes) {
                    lastReported = total
                    onProgress(TransferProgress(TransferProgress.Phase.DOWNLOADING, total, declared))
                }
            }
        }
        onProgress(TransferProgress(TransferProgress.Phase.DOWNLOADING, total, declared ?: total))
        if (declared != null && total != declared) {
            throw TransferException(Kind.NETWORK, "The download ended early ($total of $declared bytes).")
        }
        if (total == 0L) throw TransferException(Kind.SERVER, "GitHub sent an empty file.")
    }

    private suspend fun <T> network(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TransferException) {
        throw e
    } catch (e: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        throw TransferException(Kind.NETWORK, "GitHub took too long to answer. Check your connection and try again.", e)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw TransferException(Kind.NETWORK, "Could not reach GitHub. Check your internet connection.", e)
    }

    private fun apiHeaders(token: String?): Map<String, String> {
        val headers = linkedMapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to API_VERSION,
            "User-Agent" to USER_AGENT,
        )
        if (token != null) headers["Authorization"] = "Bearer $token"
        return headers
    }

    private fun apiError(r: StreamResponse): TransferException = when {
        r.code == 401 -> {
            onUnauthorized()
            TransferException(Kind.AUTH, "GitHub rejected the saved sign-in (expired or revoked). Please connect again.")
        }
        r.code == 429 || (r.code == 403 && (r.header("x-ratelimit-remaining") == "0" || r.header("retry-after") != null)) ->
            TransferException(Kind.RATE_LIMITED, "GitHub rate limit reached. Try again in a few minutes.")
        r.code == 403 -> TransferException(Kind.AUTH, "GitHub refused the download. Your sign-in needs read access to this repository's contents.")
        r.code == 404 -> TransferException(Kind.NOT_FOUND, "The repository or branch was not found, or your sign-in cannot access it.")
        r.code in 500..599 -> TransferException(Kind.SERVER, "GitHub is having problems (HTTP ${r.code}). Try again in a moment.")
        else -> TransferException(Kind.SERVER, "Unexpected response from GitHub (HTTP ${r.code}).")
    }

    /** Accepts only an https URL without embedded credentials. The URL itself is never put into a message. */
    private fun checkedRedirect(location: String?, base: String): String {
        val bad = { TransferException(Kind.SERVER, "GitHub's download redirect was missing or not secure, so it was not followed.") }
        if (location.isNullOrBlank()) throw bad()
        val uri = try {
            URI(base).resolve(location.trim())
        } catch (e: URISyntaxException) {
            throw bad()
        } catch (e: IllegalArgumentException) {
            throw bad()
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.host.isNullOrEmpty() || uri.userInfo != null) throw bad()
        return uri.toString()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encPath(s: String): String = s.split('/').joinToString("/") { enc(it) }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val BUFFER = 32 * 1024
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "AlmusAppCreatorPro"
    }
}
