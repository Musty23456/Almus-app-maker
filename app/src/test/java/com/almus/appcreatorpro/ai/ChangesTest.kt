package com.almus.appcreatorpro.ai

import com.almus.appcreatorpro.debug.AiPatchEdit
import com.almus.appcreatorpro.debug.DiffKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangesTest {
    // ---- diff
    @Test fun `diff marks replaced, added and removed lines`() {
        val d = LineDiff.diff("a\nb\nc\n", "a\nB\nc\n")
        assertEquals(listOf(DiffKind.CONTEXT, DiffKind.REMOVED, DiffKind.ADDED, DiffKind.CONTEXT), d.map { it.kind })
        assertEquals(DiffStats(1, 1), LineDiff.stats(d))
        assertEquals(DiffStats(2, 0), LineDiff.stats(LineDiff.diff(null, "x\ny")))
        assertEquals(DiffStats(1, 0), LineDiff.stats(LineDiff.diff("a\nb", "a\nb\nc")))
        assertEquals(DiffStats(0, 0), LineDiff.stats(LineDiff.diff("a\r\nb\r\n", "a\nb\n")))
    }

    @Test fun `diff of two large unrelated files falls back without exploding`() {
        val a = (1..3000).joinToString("\n") { "old $it" }; val b = (1..3000).joinToString("\n") { "new $it" }
        assertEquals(DiffStats(3000, 3000), LineDiff.stats(LineDiff.diff(a, b)))
    }

    @Test fun `collapse keeps three lines of context and marks gaps between distant changes`() {
        val before = (1..40).joinToString("\n") { "l$it" }
        val one = LineDiff.collapse(LineDiff.diff(before, before.replace("l20\n", "L20\n")), 3)
        assertEquals(8, one.size) // 3 before, removed, added, 3 after
        assertTrue(one.none { it == null })
        val two = LineDiff.collapse(LineDiff.diff(before, before.replace("l5\n", "X5\n").replace("l35\n", "X35\n")), 3)
        assertEquals(1, two.count { it == null })
        assertNotNull(two.first()); assertNotNull(two.last())
    }

    // ---- resolver
    private val files get() = FakeProjectFiles(mapOf("a/Login.kt" to "package a\n\nfun login() = 1\n", "b.txt" to "keep"))
    private suspend fun resolve(f: FakeProjectFiles, vararg c: RawChange) = ChangeResolver.fromProposal(EditProposal("sum", c.toList()), f, "title", 1000L)
    private fun full(path: String, content: String, action: String = "modify") = RawChange(path, action, "why", content, emptyList())
    private fun edits(path: String, vararg e: Pair<String, String>) = RawChange(path, "modify", "why", null, e.map { AiPatchEdit(it.first, it.second) })

    @Test fun `full content on an existing file becomes a modify with before and after`() = runTest {
        val r = resolve(files, full("a/Login.kt", "package a\n\nfun login() = 2\n"))
        val c = r.changeSet!!.changes.single()
        assertEquals(ChangeKind.MODIFY, c.kind); assertEquals("package a\n\nfun login() = 1\n", c.before); assertEquals(1, c.added); assertEquals(1, c.removed)
        assertNotNull(c.baseHash); assertTrue(r.rejected.isEmpty())
    }

    @Test fun `a new file is a create without base hash`() = runTest {
        val c = resolve(files, full("a/New.kt", "package a\n", "create")).changeSet!!.changes.single()
        assertEquals(ChangeKind.CREATE, c.kind); assertNull(c.before); assertNull(c.baseHash)
    }

    @Test fun `edits must match exactly once`() = runTest {
        val f = FakeProjectFiles(mapOf("x.kt" to "val a = 1\nval b = 1\n"))
        assertEquals("val a = 2\nval b = 1\n", resolve(f, edits("x.kt", "val a = 1" to "val a = 2")).changeSet!!.changes.single().after)
        assertTrue(resolve(f, edits("x.kt", "= 1" to "= 2")).rejected.single().reason.contains("more than once"))
        assertTrue(resolve(f, edits("x.kt", "missing" to "y")).rejected.single().reason.contains("not found"))
        assertTrue(resolve(f, edits("nope.kt", "a" to "b")).rejected.single().reason.contains("existing file"))
        assertTrue(resolve(f, edits("x.kt", "" to "b")).rejected.single().reason.contains("no text to find"))
    }

    @Test fun `unsafe, unchanged, empty and secret-bearing changes are rejected, good ones kept`() = runTest {
        val r = resolve(
            files,
            full("../x.kt", "a"), full("a/Login.kt", "package a\n\nfun login() = 1\n"), full("c.kt", "   "),
            full("d.kt", "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuvw\""), full("local.properties", "sdk.dir=/x"),
            full("ok.kt", "val ok = 1"), full("OK.KT", "dup"),
        )
        assertEquals(listOf("ok.kt"), r.changeSet!!.paths)
        assertEquals(setOf("../x.kt", "a/Login.kt", "c.kt", "d.kt", "local.properties", "OK.KT"), r.rejected.map { it.path }.toSet())
    }

    @Test fun `nothing usable gives no change set`() = runTest {
        assertNull(resolve(files, full("../x", "a")).changeSet)
    }

    @Test fun `windows line endings of the original are kept`() = runTest {
        val f = FakeProjectFiles(mapOf("w.kt" to "a\r\nb\r\n"))
        val c = resolve(f, full("w.kt", "a\nB\n")).changeSet!!.changes.single()
        assertEquals("a\r\nB\r\n", c.after); assertEquals(1, c.added)
    }

    @Test fun `a secret that was already in the file does not block an unrelated edit`() = runTest {
        val old = "val k = \"AIzaSyA1234567890abcdefghijklmnopqrstuvw\"\nval x = 1\n"
        val c = resolve(FakeProjectFiles(mapOf("k.kt" to old)), full("k.kt", old.replace("x = 1", "x = 2"))).changeSet
        assertNotNull(c)
    }

    @Test fun `generated project against a fresh project shows template files as modified`() = runTest {
        val f = FakeProjectFiles(mapOf("app/src/main/AndroidManifest.xml" to "<manifest/>"))
        val p = GeneratedProject("N", "com.x", "s", listOf(GeneratedFile("app/src/main/AndroidManifest.xml", GOOD_MANIFEST), GeneratedFile("app/New.kt", "class N")), emptyList())
        val set = ChangeResolver.fromGenerated(p, f, 5L).changeSet!!
        assertEquals(listOf(ChangeKind.MODIFY, ChangeKind.CREATE), set.changes.map { it.kind })
    }
}
