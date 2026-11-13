package com.almus.appcreatorpro.debug

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

/** What the "AI debugging" card shows. Nothing is sent until the user confirms. */
sealed interface AiUi {
    data object Idle : AiUi

    /** The request (redacted log excerpt, code snippets) is being built. */
    data object Preparing : AiUi

    data class Confirm(val request: AiDebugRequest) : AiUi

    /** [request] is in flight; it can be cancelled. */
    data class Working(val request: AiDebugRequest) : AiUi

    /** [request] is kept so "Retry" can send exactly what the user already confirmed. It is null when building the request failed. */
    data class Done(val outcome: AiOutcome, val request: AiDebugRequest?) : AiUi
}

/**
 * The life cycle of one AI debugging request, without Android, so it is unit-tested on the JVM.
 *
 *  - ONE request at a time: [prepare] and [send] are ignored while one is running (double taps, a second button).
 *  - Work runs on [workContext] (not the main thread) and under a hard deadline ([timeoutMillis]).
 *  - [cancel] stops the request, and the network call with it; the screen goes back to [AiUi.Idle].
 *  - [retry] sends the same, already confirmed request again.
 *  - The state can never stay on "working": every way out of a request (answer, failure, timeout, cancellation) leaves it.
 *  - Failures are values ([AiOutcome.Failed]); the exception behind them goes to [errorSink]. Only cancellation is
 *    rethrown, and only [Error]s that signal a broken app are not turned into a message (see [AiFailures.isRecoverable]).
 *
 * It never touches project files: the answer's patches only become pending suggestions inside [DebugSession.askAi].
 */
class AiDebugController(
    private val scope: CoroutineScope,
    private val sessionProvider: () -> DebugSession?,
    private val workContext: CoroutineContext = Dispatchers.Default,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MS,
    private val errorSink: (String, Throwable) -> Unit = DefaultErrorSink,
) {
    private val lock = Any()
    private val _state = MutableStateFlow<AiUi>(AiUi.Idle)
    val state: StateFlow<AiUi> = _state.asStateFlow()

    private var job: Job? = null
    private var lastPrepare: Pair<AiTask, Boolean>? = null

    val isBusy: Boolean get() = _state.value.let { it is AiUi.Preparing || it is AiUi.Working }

    /** Builds the request WITHOUT sending it; the screen then asks the user to confirm. Returns false if ignored. */
    fun prepare(task: AiTask, includeCode: Boolean): Boolean {
        val session = sessionProvider()
        synchronized(lock) {
            if (isBusy) return false
            if (session == null) {
                _state.value = AiUi.Done(AiOutcome.Failed("The debug session is still loading. Try again in a moment.", true), null)
                return false
            }
            lastPrepare = task to includeCode
            _state.value = AiUi.Preparing
            launchLocked { me ->
                val next: AiUi = try {
                    AiUi.Confirm(session.buildAiRequest(task, includeCode))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!AiFailures.isRecoverable(e)) throw e
                    errorSink("The AI request could not be prepared", e)
                    AiUi.Done(AiFailures.outcome(e), null)
                }
                finish(me, next)
            }
        }
        return true
    }

    /** Sends [request] (after the user confirmed it). Returns false if another request is already running. */
    fun send(request: AiDebugRequest): Boolean {
        val session = sessionProvider()
        synchronized(lock) {
            if (isBusy) return false
            if (session == null) {
                _state.value = AiUi.Done(AiOutcome.Failed("The debug session is still loading. Try again in a moment.", true), request)
                return false
            }
            _state.value = AiUi.Working(request)
            launchLocked { me ->
                val outcome: AiOutcome = try {
                    withTimeout(timeoutMillis) { session.askAi(request) }
                } catch (e: TimeoutCancellationException) {
                    // Our own deadline. If instead this coroutine was cancelled, ensureActive() rethrows that.
                    currentCoroutineContext().ensureActive()
                    AiOutcome.Failed("The AI did not answer within ${timeoutMillis / 1000} seconds. Your project was not changed. Try again.", true, e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (!AiFailures.isRecoverable(e)) throw e
                    AiFailures.outcome(e)
                }
                (outcome as? AiOutcome.Failed)?.cause?.let { errorSink("The AI debug request failed", it) }
                finish(me, AiUi.Done(outcome, request))
            }
        }
        return true
    }

    /** Sends the last request again, or, if building it failed, builds it again. Returns false if nothing could be started. */
    fun retry(): Boolean {
        val (request, prepared) = synchronized(lock) {
            if (isBusy) return false
            (_state.value as? AiUi.Done)?.request to lastPrepare
        }
        return when {
            request != null -> send(request)
            prepared != null -> prepare(prepared.first, prepared.second)
            else -> false
        }
    }

    /** Stops whatever is running (the network call is cancelled too) and returns to [AiUi.Idle]. Also closes a finished answer. */
    fun cancel() {
        val running = synchronized(lock) {
            val j = job
            job = null // the running coroutine's own finish() then does nothing
            _state.value = AiUi.Idle
            j
        }
        running?.cancel()
    }

    private fun launchLocked(block: suspend (Job) -> Unit) {
        val j = scope.launch(workContext, start = CoroutineStart.LAZY) {
            val me = currentCoroutineContext()[Job]!!
            try {
                block(me)
            } finally {
                // Whatever happened (cancelled by the screen, a rethrown Error): never leave "working" on screen.
                synchronized(lock) {
                    if (job === me) {
                        job = null
                        if (_state.value.let { it is AiUi.Preparing || it is AiUi.Working }) _state.value = AiUi.Idle
                    }
                }
            }
        }
        job = j
        j.start()
    }

    private fun finish(me: Job, next: AiUi) = synchronized(lock) {
        if (job === me) {
            job = null
            _state.value = next
        }
    }

    companion object {
        /** Gemini can think for a while, but nobody should wait longer than this without an answer. */
        const val DEFAULT_TIMEOUT_MS = 180_000L
    }
}
