package com.almus.appcreatorpro.testutil

import com.almus.appcreatorpro.service.artifacts.StreamResponse
import com.almus.appcreatorpro.service.artifacts.StreamTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds a zip in memory. */
fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return bytes.toByteArray()
}

/** A believable APK: a zip with AndroidManifest.xml and some payload. */
fun fakeApk(payload: String = "classes"): ByteArray =
    zipOf("AndroidManifest.xml" to "<manifest/>".toByteArray(), "classes.dex" to payload.toByteArray())

/** An artifact zip as GitHub serves it, containing one APK. */
fun artifactZip(apkName: String = "app-debug.apk", apk: ByteArray = fakeApk()): ByteArray = zipOf(apkName to apk)

/** Input stream that delivers [good] bytes and then fails like a dropped connection. */
class BrokenInput(private val data: ByteArray, private val failAfter: Int) : InputStream() {
    private var pos = 0
    override fun read(): Int = throw UnsupportedOperationException()
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= failAfter) throw IOException("connection reset")
        val n = minOf(len, failAfter - pos, data.size - pos)
        if (n <= 0) return -1
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}

class SentRequest(val url: String, val headers: Map<String, String>)

/** What the fake server answers for one request. */
class Reply(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: () -> InputStream = { ByteArrayInputStream(ByteArray(0)) },
    /** Called before the body is handed to the downloader (to simulate things happening mid-request). */
    val before: suspend () -> Unit = {},
    val throws: IOException? = null,
) {
    companion object {
        fun redirect(to: String?, code: Int = 302) = Reply(code, if (to == null) emptyMap() else mapOf("location" to to))
        fun ok(bytes: ByteArray, announce: Boolean = true) = Reply(
            200,
            if (announce) mapOf("content-length" to bytes.size.toString()) else emptyMap(),
            { ByteArrayInputStream(bytes) },
        )
        fun status(code: Int, headers: Map<String, String> = emptyMap()) = Reply(code, headers)
    }
}

/** Scripted download server. Replies are consumed in order (the last repeats). Unscripted requests fail the test. */
class FakeStreamTransport(vararg replies: Reply) : StreamTransport {
    val requests = mutableListOf<SentRequest>()
    private val replies = replies.toMutableList()
    private var index = 0

    fun reset(vararg next: Reply) {
        replies.clear(); replies.addAll(next); index = 0; requests.clear()
    }

    override suspend fun <T> get(url: String, headers: Map<String, String>, block: suspend (StreamResponse) -> T): T {
        requests += SentRequest(url, headers.mapKeys { it.key.lowercase() })
        val reply = replies.getOrNull(minOf(index, replies.lastIndex)) ?: throw AssertionError("Unexpected request: $url")
        index++
        reply.throws?.let { throw it }
        reply.before()
        return reply.body().use { stream ->
            block(StreamResponse(reply.code, reply.headers.mapKeys { it.key.lowercase() }, stream))
        }
    }
}

/** Cancels the calling coroutine (simulating the user pressing Cancel) right before the body is read. */
fun cancelCaller(): suspend () -> Unit = { currentCoroutineContext().job.cancel() }

/** A gate to hold a download open until the test lets it continue. */
class Gate {
    private val latch = CompletableDeferred<Unit>()
    suspend fun await() = latch.await()
    fun open() { latch.complete(Unit) }
}
