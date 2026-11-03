package com.almus.appcreatorpro.monitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Reads project files (Gradle runs unit tests from app/). Skipped, not failed, if a file is not where expected. */
class Phase7GuardsTest {
    private fun find(vararg candidates: String): File? = candidates.map(::File).firstOrNull { it.exists() }

    private val manifest get() = find("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")

    @Test
    fun the_manifest_asks_for_the_notification_permission_and_nothing_that_needs_special_approval() {
        val text = (manifest ?: return assumeTrue("manifest not found", false)).readText()
        assertTrue(text.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue(text.contains("android.permission.INTERNET"))
        // WorkManager does the scheduling: no own foreground service, exact alarms or battery-optimisation exemption.
        for (forbidden in listOf("FOREGROUND_SERVICE", "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "WAKE_LOCK")) {
            assertFalse("manifest must not declare $forbidden", text.contains("android.permission.$forbidden"))
        }
        assertFalse("allowBackup must stay off", text.contains("allowBackup=\"true\""))
    }

    @Test
    fun the_app_uses_workmanager() {
        val gradle = find("build.gradle.kts", "app/build.gradle.kts") ?: return assumeTrue("gradle file not found", false)
        assertTrue(gradle.readText().contains("libs.androidx.work.runtime.ktx"))
    }

    @Test
    fun the_status_bar_icon_exists() {
        val icon = find("src/main/res/drawable/ic_stat_build.xml", "app/src/main/res/drawable/ic_stat_build.xml")
        assertTrue(icon != null)
    }
}
