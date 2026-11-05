package com.almus.appcreatorpro.debug

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Port for live Logcat. NOT implemented in Phase 8: the app has no way to read the log of another app on a normal
 * (non-root, non-ADB) phone. Android lets an app read only its own process's log (`logcat` run by the app), and
 * `READ_LOGS` is a system/ADB-granted permission. So nothing in Phase 8 pretends to show runtime logs.
 *
 * To add it later: implement this interface (for example by running `logcat -v threadtime` for the app's own pid, or
 * by receiving lines from a debug agent compiled into the app under test), feed lines through [LogcatLineParser] and
 * hand the resulting [LogEntry] values to the same log viewer, filters and export that build logs already use.
 */
interface LogcatSource {
    /** Whether lines can be read in the current environment, and if not, why (shown to the user). */
    val availability: LogcatAvailability

    /** Raw logcat lines (threadtime format). Cold flow: collecting starts reading, cancelling stops it. */
    fun lines(): Flow<String>
}

sealed interface LogcatAvailability {
    data object Available : LogcatAvailability
    data class Unavailable(val reason: String) : LogcatAvailability
}

/** The source used today. */
object UnavailableLogcatSource : LogcatSource {
    override val availability: LogcatAvailability = LogcatAvailability.Unavailable(
        "Live Logcat is not available: Android only lets an app read its own log, and reading other apps needs ADB or a debug agent. " +
            "Build logs and static checks work without it.",
    )

    override fun lines(): Flow<String> = emptyFlow()
}

/** Parses `logcat -v threadtime` lines: `11-01 17:42:10.123  1234  5678 E Tag: message`. */
object LogcatLineParser {
    private val threadtime = Regex("^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\d+)\\s+(\\d+)\\s+([VDIWEFA])\\s+(.*?)\\s*:\\s(.*)$")

    fun parse(line: String, index: Int): LogEntry? {
        val m = threadtime.matchEntire(line.trimEnd()) ?: return null
        val level = when (m.groupValues[4]) {
            "V", "D" -> LogLevel.DEBUG
            "I" -> LogLevel.INFO
            "W" -> LogLevel.WARNING
            else -> LogLevel.ERROR // E, F, A
        }
        return LogEntry(index, level, LogCleaner.redact(m.groupValues[6]), timestamp = m.groupValues[1], tag = m.groupValues[5], source = LogSource.LOGCAT)
    }

    fun parseAll(text: String): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        for (line in text.lineSequence()) parse(line, out.size)?.let { out += it }
        return out
    }
}
