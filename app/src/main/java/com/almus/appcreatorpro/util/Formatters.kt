package com.almus.appcreatorpro.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formatters {

    /** 1536 -> "1.5 KB". Uses 1024-based units and a fixed locale so output is stable. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "${bytes.coerceAtLeast(0L)} B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
    }

    /** 125 -> "2m 05s", 42 -> "42s". */
    fun formatDuration(totalSeconds: Int): String {
        val seconds = totalSeconds.coerceAtLeast(0)
        val minutes = seconds / 60
        val rest = seconds % 60
        return if (minutes == 0) "${rest}s" else String.format(Locale.US, "%dm %02ds", minutes, rest)
    }

    /** 5 Mar 2026. Zone and locale are parameters so the result is testable. */
    fun formatDate(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

    /** 5 Mar 2026, 14:07. Zone and locale are parameters so the result is testable. */
    fun formatDateTime(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale).format(Instant.ofEpochMilli(millis).atZone(zone))

    /** "in 12 d", "in 5 h", "in 20 min", or "expired" once [expiresAtMillis] has passed. */
    fun timeUntil(nowMillis: Long, expiresAtMillis: Long): String {
        val diff = expiresAtMillis - nowMillis
        if (diff <= 0L) return "expired"
        val minutes = diff / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> "in under a minute"
            minutes < 60L -> "in $minutes min"
            hours < 24L -> "in $hours h"
            else -> "in $days d"
        }
    }

    /** Short "time ago" text such as "just now", "5 min ago", "3 h ago", "2 d ago". */
    fun relativeTime(nowMillis: Long, thenMillis: Long): String {
        val diff = (nowMillis - thenMillis).coerceAtLeast(0L)
        val minutes = diff / 60_000L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            minutes < 1L -> "just now"
            minutes < 60L -> "$minutes min ago"
            hours < 24L -> "$hours h ago"
            else -> "$days d ago"
        }
    }
}
