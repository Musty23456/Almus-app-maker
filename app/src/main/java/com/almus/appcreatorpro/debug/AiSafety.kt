package com.almus.appcreatorpro.debug

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

/**
 * One place that turns "something unexpected was thrown" into a message a person can act on.
 *
 * The AI debugger runs inside `viewModelScope`; an exception that nobody catches there ends the whole app process.
 * Everything below the screen therefore reports problems as values ([AiResult.Failed] / [AiOutcome.Failed]) and keeps
 * the original exception in `cause` so the caller can log it. Nothing is swallowed: the user sees the message, the
 * log gets the stack trace.
 *
 * CancellationException is NEVER handled here: callers must rethrow it first (see [isRecoverable]).
 */
object AiFailures {

    /**
     * Problems that become a message. [Exception]s, plus the two [Error]s that large inputs can really cause on a phone
     * (out of memory, stack overflow in a regex). Every other Error (a broken class path, for example) is a bug that
     * must not be hidden, so it is rethrown.
     */
    fun isRecoverable(t: Throwable): Boolean = t is Exception || t is OutOfMemoryError || t is StackOverflowError

    /** A user-facing message for [t] and whether trying again can help. */
    fun describe(t: Throwable): Pair<String, Boolean> = when (t) {
        is OutOfMemoryError -> "The phone ran out of memory while handling this. Try again with fewer errors, or close other apps first." to true
        is StackOverflowError -> "The text was too complex to process. Try again with fewer errors." to true
        is GeneralSecurityException -> "The saved AI access code could not be read. Enter it again in AI settings." to false
        is java.net.SocketTimeoutException, is InterruptedIOException -> "The AI service did not answer in time. Try again." to true
        is UnknownHostException, is ConnectException -> "Could not reach the AI service. Check your internet connection and the backend address in AI settings." to true
        is SSLException -> "A secure connection to the AI service could not be made. Check the backend address and the date and time on your phone." to true
        is IOException -> "The connection to the AI service was interrupted. Try again." to true
        else -> "Something unexpected went wrong (${t.javaClass.simpleName}). Nothing in your project was changed. Try again." to true
    }

    fun failed(t: Throwable): AiResult.Failed = describe(t).let { (message, retry) -> AiResult.Failed(message, retry, t) }

    fun outcome(t: Throwable): AiOutcome.Failed = describe(t).let { (message, retry) -> AiOutcome.Failed(message, retry, t) }
}

/** Where unexpected exceptions go when the caller did not pass a logger: stderr, which Android shows in Logcat as "System.err". */
val DefaultErrorSink: (String, Throwable) -> Unit = { message, t ->
    System.err.println("AI-DEBUG $message: $t")
    t.printStackTrace()
}
