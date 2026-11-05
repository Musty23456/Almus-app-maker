package com.almus.appcreatorpro.domain

import com.almus.appcreatorpro.domain.error.FeatureNotImplementedException
import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.error.toUserMessage
import com.almus.appcreatorpro.domain.model.StorageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageInfoTest {

    @Test
    fun used_bytes_is_total_minus_free() {
        val info = StorageInfo(totalBytes = 1_000L, freeBytes = 400L, appDataBytes = 10L)
        assertEquals(600L, info.usedBytes)
        assertEquals(0.6f, info.usedFraction, 0.0001f)
    }

    @Test
    fun zero_total_gives_zero_fraction() {
        val info = StorageInfo(totalBytes = 0L, freeBytes = 0L, appDataBytes = 0L)
        assertEquals(0f, info.usedFraction, 0f)
    }

    @Test
    fun free_larger_than_total_never_goes_negative() {
        val info = StorageInfo(totalBytes = 100L, freeBytes = 500L, appDataBytes = 0L)
        assertEquals(0L, info.usedBytes)
        assertEquals(0f, info.usedFraction, 0f)
    }
}

class ErrorMessagesTest {

    @Test
    fun feature_not_implemented_message_names_the_feature_and_phase() {
        val message = FeatureNotImplementedException("Cloud build").toUserMessage()
        assertEquals("Cloud build is not implemented yet (planned for Phase 2).", message)
    }

    @Test
    fun validation_message_is_passed_through() {
        assertEquals("Bad name", ValidationException("Bad name").toUserMessage())
    }

    @Test
    fun unknown_errors_get_a_generic_message_without_internal_details() {
        val message = IllegalStateException("secret internal detail").toUserMessage()
        assertTrue(message.startsWith("Something went wrong"))
        assertTrue(!message.contains("secret"))
    }
}
