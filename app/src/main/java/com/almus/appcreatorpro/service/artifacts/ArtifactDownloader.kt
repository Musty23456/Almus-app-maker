package com.almus.appcreatorpro.service.artifacts

import com.almus.appcreatorpro.service.artifacts.ArtifactDownloadException.Kind
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
import java.util.zip.CRC32
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Safety limits for downloads. Defaults are generous for a debug APK and stop runaway or malicious data. */
data class DownloadLimits(
    /** Largest artifact zip accepted. */
    val maxArtifactBytes: Long = 250L * 1024 * 1024,
    /** Largest APK accepted after unzipping. Counted from the bytes really read, never from the zip's own claims. */
    val maxApkBytes: Long = 250L * 1024 * 1024,
    val maxRedirects: Int = 3,
    /** Progress is reported at most once per this many bytes. */
    val progressStepBytes: Long = 64L * 1024,
)

/**
 * Downloads the APK(s) inside one GitHub Actions artifact, securely.
 *
 * How the token is protected: GitHub answers `GET .../actions/artifacts/{id}/zip` with a redirect to a short-lived,
 * pre-signed storage URL. This class sends the Authorization header ONLY to api.github.com (first request); the
 * redirect is read by hand and followed WITHOUT the token, and only if it is https. A signed URL is never logged or
 * put into an error message.
 *
 * What is validated: HTTP status codes (expired / deleted / forbidden / rate limit / server errors), the announced
 * size against the bytes received, the size limits, the zip structure and checksums, and that every extracted file
 * really is an APK. Nothing is moved into [destDir] until everything has been verified, and a failed download never
 * damages an APK that was saved earlier.
 */
class ArtifactDownloader(
    private val transport: StreamTransport,
    private val tokenProvider: TokenProvider,
    /** Called when GitHub answers 401 so the app can clear the dead token and show "sign in again". */
    private val onUnauthorized: () -> Unit = {},
    private val baseUrl: String = "https://api.github.com",
    private val limits: DownloadLimits = DownloadLimits(),
) {

    /**
     * Downloads artifact [artifactId] of [owner]/[repo] and saves the APK(s) it contains into [destDir].
     * Returns the saved files. Throws [ArtifactDownloadException] (or CancellationException when cancelled; partial
     * data is removed in both cases).
     */
    suspend fun download(
        owner: String,
        repo: String,
        artifactId: Long,
        destDir: File,
        tempDir: File,
        onProgress: (DownloadProgress) -> Unit = {},
    ): List<File> {
        val token = tokenProvider.token()?.takeIf { it.isNotBlank() }
            ?: throw ArtifactDownloadException(Kind.NOT_CONNECTED, "You are not signed in to GitHub. Connect GitHub first.")
        if (!tempDir.isDirectory && !tempDir.mkdirs()) {
            throw ArtifactDownloadException(Kind.STORAGE, "Could not prepare temporary storage on this phone.")
        }
        val zipPart = try {
            File.createTempFile("artifact-", ".zip.part", tempDir)
        } catch (e: IOException) {
            throw ArtifactDownloadException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
        }
        try {
            onProgress(DownloadProgress(DownloadProgress.Phase.CONNECTING))
            fetchZip(owner, repo, artifactId, token, zipPart, onProgress)
            onProgress(DownloadProgress(DownloadProgress.Phase.VERIFYING, zipPart.length(), zipPart.length()))
            checkZipMagic(zipPart)
            return extractApks(zipPart, destDir, tempDir, onProgress)
        } finally {
            zipPart.delete()
        }
    }

    // ------------------------------------------------------------------ network

    private suspend fun fetchZip(
        owner: String,
        repo: String,
        artifactId: Long,
        token: String,
        target: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val firstUrl = "$baseUrl/repos/${enc(owner)}/${enc(repo)}/actions/artifacts/$artifactId/zip"
        var next: String? = null
        var done = false
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
            val url = next ?: throw ArtifactDownloadException(Kind.BAD_REDIRECT, "GitHub did not say where to download the artifact from.")
            next = null
            network {
                // No Authorization header here: this host is not GitHub's API.
                transport.get(url, storageHeaders()) { r ->
                    when {
                        r.code == 200 -> {
                            copyBody(r, target, onProgress)
                            done = true
                        }
                        r.code in REDIRECTS -> next = checkedRedirect(r.header("location"), url)
                        else -> throw storageError(r.code)
                    }
                    Unit
                }
            }
            if (!done && ++hops > limits.maxRedirects) {
                throw ArtifactDownloadException(Kind.BAD_REDIRECT, "The download was redirected too many times.")
            }
        }
    }

    private suspend fun copyBody(response: StreamResponse, target: File, onProgress: (DownloadProgress) -> Unit) {
        val declared = response.contentLength
        if (declared != null && declared > limits.maxArtifactBytes) {
            throw ArtifactDownloadException(Kind.TOO_LARGE, "The artifact is ${declared / (1024 * 1024)} MB, more than the ${limits.maxArtifactBytes / (1024 * 1024)} MB limit.")
        }
        val output = try {
            FileOutputStream(target)
        } catch (e: IOException) {
            throw ArtifactDownloadException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
        }
        var total = 0L
        output.use { out ->
            val buffer = ByteArray(BUFFER)
            var lastReported = 0L
            onProgress(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, 0L, declared))
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = try {
                    response.body.read(buffer)
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw ArtifactDownloadException(Kind.INTERRUPTED, "The connection was lost during the download.", e)
                }
                if (n < 0) break
                total += n
                if (total > limits.maxArtifactBytes) {
                    throw ArtifactDownloadException(Kind.TOO_LARGE, "The download is larger than the ${limits.maxArtifactBytes / (1024 * 1024)} MB limit and was stopped.")
                }
                try {
                    out.write(buffer, 0, n)
                } catch (e: IOException) {
                    throw ArtifactDownloadException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
                }
                if (total - lastReported >= limits.progressStepBytes) {
                    lastReported = total
                    onProgress(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, total, declared))
                }
            }
        }
        onProgress(DownloadProgress(DownloadProgress.Phase.DOWNLOADING, total, declared ?: total))
        if (declared != null && total != declared) {
            throw ArtifactDownloadException(Kind.INTERRUPTED, "The download ended early ($total of $declared bytes).")
        }
        if (total == 0L) {
            throw ArtifactDownloadException(Kind.CORRUPT, "GitHub sent an empty file.")
        }
    }

    private suspend fun <T> network(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArtifactDownloadException) {
        throw e
    } catch (e: SocketTimeoutException) {
        currentCoroutineContext().ensureActive()
        throw ArtifactDownloadException(Kind.NETWORK, "GitHub took too long to answer. Check your connection and try again.", e)
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        throw ArtifactDownloadException(Kind.NETWORK, "Could not reach GitHub. Check your internet connection.", e)
    }

    private fun apiHeaders(token: String) = mapOf(
        "Accept" to "application/vnd.github+json",
        "Authorization" to "Bearer $token",
        "X-GitHub-Api-Version" to API_VERSION,
        "User-Agent" to USER_AGENT,
    )

    private fun storageHeaders() = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/octet-stream")

    private fun apiError(r: StreamResponse): ArtifactDownloadException = when {
        r.code == 401 -> {
            onUnauthorized()
            ArtifactDownloadException(Kind.TOKEN_REJECTED, "GitHub rejected the saved sign-in (expired or revoked). Please connect again.")
        }
        r.code == 429 || (r.code == 403 && (r.header("x-ratelimit-remaining") == "0" || r.header("retry-after") != null)) ->
            ArtifactDownloadException(Kind.RATE_LIMITED, "GitHub rate limit reached. Try again in a few minutes.")
        r.code == 403 -> ArtifactDownloadException(
            Kind.FORBIDDEN,
            "GitHub refused the download. Your sign-in needs read access to Actions on this repository.",
        )
        r.code == 404 -> ArtifactDownloadException(
            Kind.NOT_FOUND,
            "This artifact no longer exists on GitHub (it or its run was deleted).",
        )
        r.code == 410 -> ArtifactDownloadException(
            Kind.EXPIRED,
            "This artifact has expired. GitHub deletes artifacts after their retention period; run the build again.",
        )
        r.code in 500..599 -> ArtifactDownloadException(Kind.SERVER, "GitHub is having problems (HTTP ${r.code}). Try again in a moment.")
        else -> ArtifactDownloadException(Kind.UNKNOWN, "Unexpected response from GitHub (HTTP ${r.code}).")
    }

    private fun storageError(code: Int): ArtifactDownloadException = when (code) {
        400, 401, 403, 404, 410 -> ArtifactDownloadException(
            Kind.LINK_REJECTED,
            "GitHub's temporary download link was refused or has expired. Try again.",
        )
        in 500..599 -> ArtifactDownloadException(Kind.SERVER, "GitHub's download server is having problems (HTTP $code). Try again in a moment.")
        else -> ArtifactDownloadException(Kind.UNKNOWN, "Unexpected response from the download server (HTTP $code).")
    }

    /** Accepts only an https URL without embedded credentials. The URL itself is never put into a message. */
    private fun checkedRedirect(location: String?, base: String): String {
        val bad = { ArtifactDownloadException(Kind.BAD_REDIRECT, "GitHub's download redirect was missing or not secure, so it was not followed.") }
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

    // ------------------------------------------------------------------ verification and extraction

    private fun checkZipMagic(file: File) {
        val head = ByteArray(4)
        val read = try {
            file.inputStream().use { it.read(head) }
        } catch (e: IOException) {
            throw ArtifactDownloadException(Kind.STORAGE, "Could not read the downloaded file.", e)
        }
        val ok = read == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4
        if (!ok) throw ArtifactDownloadException(Kind.CORRUPT, "The downloaded file is not a valid artifact (it is not a zip file).")
    }

    private suspend fun extractApks(zipFile: File, destDir: File, tempDir: File, onProgress: (DownloadProgress) -> Unit): List<File> {
        val zip = try {
            ZipFile(zipFile)
        } catch (e: ZipException) {
            throw ArtifactDownloadException(Kind.CORRUPT, "The downloaded artifact is damaged (invalid zip).", e)
        } catch (e: IOException) {
            throw ArtifactDownloadException(Kind.CORRUPT, "The downloaded artifact could not be opened.", e)
        }
        val staged = ArrayList<Pair<File, String>>()
        val result: List<File>
        try {
            result = zip.use { z ->
                val entries = z.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    .toList()
                if (entries.isEmpty()) throw ArtifactDownloadException(Kind.NO_APK, "This artifact does not contain an APK file.")
                if (!destDir.isDirectory && !destDir.mkdirs()) {
                    throw ArtifactDownloadException(Kind.STORAGE, "Could not create the folder for the APK on this phone.")
                }
                onProgress(DownloadProgress(DownloadProgress.Phase.SAVING))
                val used = HashSet<String>()
                for (entry in entries) {
                    currentCoroutineContext().ensureActive()
                    // Only the base name is used, and it is sanitised: entry paths (e.g. "../x") can never choose where a file goes.
                    val name = uniqueName(safeName(entry.name.substringAfterLast('/')), used)
                    val part = try {
                        File.createTempFile("apk-", ".part", tempDir)
                    } catch (e: IOException) {
                        throw ArtifactDownloadException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
                    }
                    staged += part to name
                    copyEntry(z, entry, part)
                    verifyApk(part)
                }
                // Everything verified: only now touch the destination folder.
                val saved = ArrayList<File>()
                for ((part, name) in staged) {
                    val target = File(destDir, name)
                    if (target.exists() && !target.delete()) {
                        throw ArtifactDownloadException(Kind.STORAGE, "Could not replace the earlier copy of $name.")
                    }
                    if (!part.renameTo(target)) {
                        try {
                            part.copyTo(target, overwrite = true)
                        } catch (e: IOException) {
                            target.delete()
                            throw ArtifactDownloadException(Kind.STORAGE, "Could not save the APK on this phone. Is the storage full?", e)
                        }
                    }
                    saved += target
                }
                saved
            }
        } finally {
            staged.forEach { it.first.delete() }
        }
        return result
    }

    private suspend fun copyEntry(zip: ZipFile, entry: java.util.zip.ZipEntry, target: File) {
        val crc = CRC32()
        var total = 0L
        try {
            zip.getInputStream(entry).use { input ->
                FileOutputStream(target).use { out ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = try {
                            input.read(buffer)
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw ArtifactDownloadException(Kind.CORRUPT, "The APK inside the artifact is damaged.", e)
                        }
                        if (n < 0) break
                        total += n
                        if (total > limits.maxApkBytes) {
                            throw ArtifactDownloadException(Kind.TOO_LARGE, "The APK is larger than the ${limits.maxApkBytes / (1024 * 1024)} MB limit.")
                        }
                        crc.update(buffer, 0, n)
                        try {
                            out.write(buffer, 0, n)
                        } catch (e: IOException) {
                            throw ArtifactDownloadException(Kind.STORAGE, "Could not write to this phone's storage. Is it full?", e)
                        }
                    }
                }
            }
        } catch (e: ZipException) {
            throw ArtifactDownloadException(Kind.CORRUPT, "The APK inside the artifact is damaged.", e)
        }
        if (entry.crc != -1L && entry.crc != crc.value) {
            throw ArtifactDownloadException(Kind.CORRUPT, "The APK failed its integrity check (checksum mismatch). Download it again.")
        }
    }

    /** An APK is a zip that contains AndroidManifest.xml. Anything else is rejected. */
    private fun verifyApk(file: File) {
        val invalid = { ArtifactDownloadException(Kind.CORRUPT, "The file inside the artifact is not a valid APK.") }
        if (file.length() <= 0L) throw invalid()
        try {
            ZipFile(file).use { if (it.getEntry("AndroidManifest.xml") == null) throw invalid() }
        } catch (e: IOException) {
            throw invalid()
        }
    }

    private fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(100)
        return if (cleaned.isBlank() || !cleaned.endsWith(".apk", ignoreCase = true)) "app.apk" else cleaned
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        var candidate = name
        var counter = 2
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "apk")
        while (!used.add(candidate.lowercase())) {
            candidate = "${stem}_$counter.$ext"
            counter++
        }
        return candidate
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val BUFFER = 32 * 1024
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "AlmusAppCreatorPro"
    }
}
