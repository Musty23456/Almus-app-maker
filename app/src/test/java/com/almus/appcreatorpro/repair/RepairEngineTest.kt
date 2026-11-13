package com.almus.appcreatorpro.repair

import com.almus.appcreatorpro.debug.CodeEdit
import com.almus.appcreatorpro.debug.DiffKind
import com.almus.appcreatorpro.debug.FixOrigin
import com.almus.appcreatorpro.debug.LocationBasis
import com.almus.appcreatorpro.debug.TextEdits
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairEngineTest {
    private val issue = diagnose()

    @Test fun `the diagnosis names the file and line the tests rely on`() {
        val f = issue.affectedFiles.first()
        assertEquals(MAIN, f.path); assertEquals(FOO_LINE, f.line); assertTrue(f.inProject)
    }

    @Test fun `planning reads and stages but never writes the project`() = runTest {
        val env = Env()
        val plan = RepairEngine(env.files).plan(issue, listOf(proposal(issue, "1")))
        assertTrue(env.files.writes.isEmpty())
        assertEquals(MAIN_TEXT, env.files.files[MAIN])
        val patch = plan.patches.single()
        assertEquals(MAIN, patch.path)
        assertEquals(MAIN_TEXT, patch.change.before)
        assertTrue(patch.change.after.contains("println(\"foo\")"))
        assertEquals(1, patch.added); assertEquals(1, patch.removed)
        assertEquals(ValidationStatus.PASSED, patch.validation.status)
    }

    @Test fun `the diff is line by line and minimal`() = runTest {
        val plan = RepairEngine(Env().files).plan(issue, listOf(proposal(issue, "1")))
        val changed = plan.patches.single().change.diff.filter { it.kind != DiffKind.CONTEXT }
        assertEquals(2, changed.size)
        assertEquals(FOO_LINE, changed.first { it.kind == DiffKind.REMOVED }.oldLine)
        assertEquals("        foo()", changed.first { it.kind == DiffKind.REMOVED }.text)
    }

    @Test fun `affected files come from the diagnosis and are checked against the project`() = runTest {
        val plan = RepairEngine(Env().files).plan(issue, listOf(proposal(issue, "1")))
        val t = plan.targets.first()
        assertEquals(MAIN, t.path); assertEquals(LocationBasis.LOG_LOCATION, t.basis); assertTrue(t.exists)
    }

    @Test fun `a patch near the reported line says so`() = runTest {
        val near = RepairEngine(Env().files).plan(issue, listOf(proposal(issue, "1"))).patches.single()
        assertEquals(true, near.nearReportedLine)
        val far = RepairEngine(Env().files).plan(issue, listOf(proposal(issue, "2", edit = CodeEdit.replace(3, "import android.app.Application")))).patches.single()
        assertEquals(false, far.nearReportedLine)
        assertTrue(far.locationNote!!.contains("Check that it addresses"))
    }

    @Test fun `without a suggestion there is no patch and nothing is invented`() = runTest {
        val env = Env()
        val plan = RepairEngine(env.files).plan(issue, emptyList())
        assertTrue(plan.patches.isEmpty())
        assertTrue(plan.notices.contains(RepairText.NO_PATCH))
        assertTrue(env.files.writes.isEmpty())
    }

    @Test fun `suggestions for other errors and other files are not pulled in`() = runTest {
        val other = proposal(null, "x", path = "app/build.gradle.kts", text = "plugins {}\n", edit = CodeEdit.replace(1, "plugins { }"))
        val env = Env(mapOf(MAIN to MAIN_TEXT, UTIL to UTIL_TEXT, "app/build.gradle.kts" to "plugins {}\n"))
        val plan = RepairEngine(env.files).plan(issue, listOf(other))
        assertTrue(plan.patches.isEmpty())
    }

    @Test fun `a suggestion written for an older version of the file is skipped`() = runTest {
        val env = Env(mapOf(MAIN to MAIN_TEXT + "// edited\n", UTIL to UTIL_TEXT))
        val plan = RepairEngine(env.files).plan(issue, listOf(proposal(issue, "1")))
        assertTrue(plan.patches.isEmpty())
        assertEquals(1, plan.skipped.size)
        assertTrue(plan.skipped.single().reason.contains("changed"))
    }

    @Test fun `overlapping suggestions keep the rule based one and report the other`() = runTest {
        val rule = proposal(issue, "rule", origin = FixOrigin.RULE)
        val ai = proposal(issue, "ai", edit = CodeEdit.replace(FOO_LINE, "        other()"), origin = FixOrigin.AI)
        val plan = RepairEngine(Env().files).plan(issue, listOf(ai, rule))
        val patch = plan.patches.single()
        assertEquals(listOf("rule"), patch.proposalIds)
        assertEquals(FixOrigin.RULE, patch.origin)
        assertEquals(1, plan.skipped.size)
        assertEquals("ai", plan.skipped.single().title.removePrefix("Fix "))
    }

    @Test fun `non overlapping suggestions for one file become one patch`() = runTest {
        val a = proposal(issue, "a")
        val b = proposal(issue, "b", edit = CodeEdit.replace(7, "        val a = 10"), origin = FixOrigin.AI)
        val patch = RepairEngine(Env().files).plan(issue, listOf(a, b)).patches.single()
        assertEquals(setOf("a", "b"), patch.proposalIds.toSet())
        assertEquals(FixOrigin.AI, patch.origin)
        assertEquals(2, patch.added)
    }

    @Test fun `a patch that breaks brackets is planned but blocked`() = runTest {
        val bad = proposal(issue, "bad", edit = CodeEdit.replace(FOO_LINE, "        foo("))
        val patch = RepairEngine(Env().files).plan(issue, listOf(bad)).patches.single()
        assertEquals(ValidationStatus.BLOCKED, patch.validation.status)
    }

    @Test fun `a patch that changes nothing is reported`() = runTest {
        val same = proposal(issue, "same", edit = CodeEdit.replace(FOO_LINE, "        foo()"))
        val plan = RepairEngine(Env().files).plan(issue, listOf(same))
        assertTrue(plan.patches.isEmpty())
        assertNotNull(plan.skipped.firstOrNull())
    }

    @Test fun `a second file of the same repair is listed as inferred`() = runTest {
        val util = proposal(issue, "u", path = UTIL, text = UTIL_TEXT, edit = CodeEdit.replace(4, "    return 1"))
        val plan = RepairEngine(Env().files).plan(issue, listOf(proposal(issue, "1"), util))
        assertEquals(2, plan.patches.size)
        assertEquals(LocationBasis.INFERRED, plan.targets.first { it.path == UTIL }.basis)
        assertNull(plan.patch(UTIL)!!.nearReportedLine)
    }

    @Test fun `hashes in the staged change match the original`() = runTest {
        val patch = RepairEngine(Env().files).plan(issue, listOf(proposal(issue, "1"))).patches.single()
        assertEquals(TextEdits.hash(MAIN_TEXT), patch.change.baseHash)
        assertFalse(patch.change.after == MAIN_TEXT)
    }
}
