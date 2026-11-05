package com.almus.appcreatorpro.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun formatBytes_small_values_use_bytes() {
        assertEquals("0 B", Formatters.formatBytes(0))
        assertEquals("1023 B", Formatters.formatBytes(1023))
    }

    @Test
    fun formatBytes_negative_is_clamped_to_zero() {
        assertEquals("0 B", Formatters.formatBytes(-5))
    }

    @Test
    fun formatBytes_scales_through_units() {
        assertEquals("1.0 KB", Formatters.formatBytes(1024))
        assertEquals("1.5 KB", Formatters.formatBytes(1536))
        assertEquals("1.0 MB", Formatters.formatBytes(1024L * 1024L))
        assertEquals("5.0 GB", Formatters.formatBytes(5L * 1024L * 1024L * 1024L))
    }

    @Test
    fun formatDuration_formats_seconds_and_minutes() {
        assertEquals("0s", Formatters.formatDuration(0))
        assertEquals("42s", Formatters.formatDuration(42))
        assertEquals("1m 00s", Formatters.formatDuration(60))
        assertEquals("2m 05s", Formatters.formatDuration(125))
    }

    @Test
    fun formatDuration_negative_is_clamped() {
        assertEquals("0s", Formatters.formatDuration(-10))
    }

    @Test
    fun relativeTime_covers_each_range() {
        val now = 10_000_000_000L
        assertEquals("just now", Formatters.relativeTime(now, now))
        assertEquals("just now", Formatters.relativeTime(now, now - 30_000L))
        assertEquals("5 min ago", Formatters.relativeTime(now, now - 5 * 60_000L))
        assertEquals("3 h ago", Formatters.relativeTime(now, now - 3 * 3_600_000L))
        assertEquals("2 d ago", Formatters.relativeTime(now, now - 2 * 86_400_000L))
    }

    @Test
    fun relativeTime_future_timestamp_is_just_now() {
        assertEquals("just now", Formatters.relativeTime(1_000L, 5_000L))
    }
}
