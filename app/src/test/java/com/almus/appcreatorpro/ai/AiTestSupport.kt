package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiAvailability
import com.almus.appcreatorpro.service.ai.PostStreamTransport
import com.almus.appcreatorpro.service.artifacts.StreamResponse
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.ByteArrayInputStream
import java.io.IOException

/** In-memory project. [failWritesTo] makes writes to those paths throw, to test rollback. */
class FakeProjectFiles(initial: Map<String, String> = emptyMap(), var failWritesTo: Set<String> = emptySet()) : AiProjectFiles {
    val files = LinkedHashMap(initial)
    val writes = mutableListOf<String>()
    override suspend fun list(): List<String> = files.keys.sorted()
    override suspend fun exists(path: String) = path in files
    override suspend fun read(path: String): String = files[path] ?: throw IllegalStateException("missing $path")
    override suspend fun write(path: String, content: String) {
        if (path in failWritesTo) throw IllegalStateException("disk full")
        files[path] = content
        writes += path
    }
    override suspend fun delete(path: String) { files.remove(path) }
}

/** Scripted backend. Answers are consumed in order; the last one repeats. Every request is recorded. */
class FakeAiBackend : AiBackend {
    sealed interface Script {
        data class Text(val text: String, val finish: String = "STOP") : Script
        data class Fail(val error: AiException) : Script
        /** Sends the partial text, then waits forever (until cancelled). */
        data class Hang(val partial: String) : Script
    }

    var availabilityValue: AiAvailability = AiAvailability.Ready
    val requests = mutableListOf<AiRequest>()
    private val scripts = ArrayDeque<Script>()
    private var last: Script? = null

    fun answer(vararg s: Script) { scripts.clear(); scripts.addAll(s) }
    fun answerText(text: String, finish: String = "STOP") = answer(Script.Text(text, finish))

    private fun next(): Script {
        val s = scripts.removeFirstOrNull() ?: last ?: error("no scripted answer")
        last = s
        return s
    }

    override suspend fun availability(): AiAvailability = availabilityValue
    override suspend fun models(): AiModelList = AiModelList("fast", listOf(AiModelInfo("fast"), AiModelInfo("pro")))

    override suspend fun complete(request: AiRequest): AiCompletion {
        requests += request
        return when (val s = next()) {
            is Script.Text -> AiCompletion(s.text, "fast", s.finish, null)
            is Script.Fail -> throw s.error
            is Script.Hang -> awaitCancellation()
        }
    }

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        requests += request
        when (val s = next()) {
            is Script.Text -> {
                emit(AiStreamEvent.Started("fast", 0))
                s.text.chunked(7).forEach { emit(AiStreamEvent.Delta(it)) }
                emit(AiStreamEvent.Finished(s.finish, null))
            }
            is Script.Fail -> throw s.error
            is Script.Hang -> { emit(AiStreamEvent.Started("fast", 0)); emit(AiStreamEvent.Delta(s.partial)); awaitCancellation() }
        }
    }
}

fun aiError(kind: AiErrorKind = AiErrorKind.UNAVAILABLE, message: String = "down", retryable: Boolean = true) = AiException(kind, message, retryable)

/** Scripted POST stream. [lines] is the raw SSE text. Records what was sent. */
class FakePostStream : PostStreamTransport {
    class Sent(val url: String, val headers: Map<String, String>, val body: String)
    class Reply(val code: Int = 200, val text: String = "", val throws: IOException? = null, val headers: Map<String, String> = emptyMap())

    val sent = mutableListOf<Sent>()
    private val replies = ArrayDeque<Reply>()
    private var last: Reply? = null
    fun reply(vararg r: Reply) { replies.clear(); replies.addAll(r) }

    override suspend fun <T> post(url: String, headers: Map<String, String>, body: String, block: suspend (StreamResponse) -> T): T {
        sent += Sent(url, headers, body)
        val r = replies.removeFirstOrNull() ?: last ?: error("no scripted reply")
        last = r
        r.throws?.let { throw it }
        return block(StreamResponse(r.code, r.headers.mapKeys { it.key.lowercase() }, ByteArrayInputStream(r.text.toByteArray())))
    }
}

fun sseOf(vararg events: Pair<String, String>): String = events.joinToString("") { (e, d) -> "event: $e\ndata: $d\n\n" }

const val GOOD_KOTLIN = "package com.example.notes\n\nclass Foo {\n    fun bar() = 1\n}\n"
const val GOOD_MANIFEST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <application android:label=\"Notes\">\n        <activity android:name=\".MainActivity\" android:exported=\"true\" />\n    </application>\n</manifest>\n"
