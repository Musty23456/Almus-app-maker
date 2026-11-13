package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.service.repair.FileRepairBackupStore
import com.almus.appcreatorpro.service.repair.FileRepairLogStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RepairStoresAndPreviewTest {
    private fun tempDir(): File = Files.createTempDirectory("repair-test").toFile().also { it.deleteOnExit() }

    private fun entry(id: String, event: RepairEvent = RepairEvent.APPLIED) = RepairLogEntry(
        id = id, atMillis = 5L, event = event, planId = "plan", applyId = "apply1", issueId = "i", issueSummary = "Unresolved reference: foo",
        path = MAIN, origin = "RULE", title = "Fix", added = 1, removed = 2, beforeHash = "aa", afterHash = "bb", proposalIds = listOf("p1", "p2"),
    )

    @Test fun `the log store round trips every field`() = runTest {
        val store = FileRepairLogStore(tempDir())
        val entries = listOf(entry("1"), entry("2", RepairEvent.ROLLED_BACK).copy(applyId = null, beforeHash = null))
        store.save("proj 1", entries)
        assertEquals(entries, store.load("proj 1"))
        assertTrue(store.load("other").isEmpty())
    }

    @Test fun `a damaged log file reads as empty`() = runTest {
        val dir = tempDir()
        File(dir, "p.json").writeText("{ not json")
        assertTrue(FileRepairLogStore(dir).load("p").isEmpty())
    }

    @Test fun `the backup store keeps the original text and never overwrites a backup`() = runTest {
        val store = FileRepairBackupStore(tempDir())
        store.save("p", "a1", mapOf(MAIN to "original \"quoted\"\nline2\n"))
        store.save("p", "a1", mapOf(MAIN to "something else"))
        assertEquals(mapOf(MAIN to "original \"quoted\"\nline2\n"), store.load("p", "a1"))
        assertNull(store.load("p", "missing"))
    }

    @Test fun `side by side pairs removed and added lines`() = runTest {
        val plan = com.almus.appcreatorpro.repair.RepairEngine(Env().files).plan(diagnose(), listOf(proposal(diagnose(), "1")))
        val p = RepairPreviews.of(plan.patches.single())
        val changed = p.sideBySide.filterNotNull().filter { it.kind != SideKind.SAME }
        assertEquals(1, changed.size)
        assertEquals(SideKind.CHANGED, changed.single().kind)
        assertEquals("        foo()", changed.single().oldText)
        assertEquals("        println(\"foo\")", changed.single().newText)
    }

    @Test fun `unchanged stretches between changes are collapsed`() = runTest {
        val long = (1..40).joinToString("\n") { "val v$it = $it" } + "\n"
        val after = long.replace("val v3 = 3", "val v3 = 33").replace("val v38 = 38", "val v38 = 3838")
        val change = com.almus.appcreatorpro.ai.ProposedChange("a.kt", com.almus.appcreatorpro.ai.ChangeKind.MODIFY, "", long, after, "h", 2, 2)
        val p = RepairPreviews.of("a.kt", change)
        assertEquals(2, p.hunks)
        assertTrue(p.unified.any { it == null })
        assertTrue(p.sideBySide.any { it == null })
    }

    @Test fun `applied repairs list rolled back state`() {
        val log = listOf(entry("3", RepairEvent.ROLLED_BACK), entry("1"), entry("2").copy(applyId = "apply2", atMillis = 9L))
        val views = RepairLogs.appliedRepairs(log)
        assertEquals(listOf("apply2", "apply1"), views.map { it.applyId })
        assertEquals(listOf(false, true), views.map { it.rolledBack })
    }
}
