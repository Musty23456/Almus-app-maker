package com.almus.appcreatorpro.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeManagerTest {
    private val original = mapOf("a.kt" to "one\n", "b.kt" to "two\n")

    private suspend fun setup(vararg extra: Pair<String, String>): Triple<FakeProjectFiles, AiChangeManager, ChangeSet> {
        val files = FakeProjectFiles(original + extra)
        val set = ChangeResolver.fromProposal(
            EditProposal("s", listOf(RawChange("a.kt", "modify", "e", "ONE\n", emptyList()), RawChange("b.kt", "modify", "e", "TWO\n", emptyList()), RawChange("new.kt", "create", "e", "new\n", emptyList()))),
            files, "AI change", 1L,
        ).changeSet!!
        val m = AiChangeManager("p1", files, InMemoryChangeHistoryStore(), clockMillis = { 50L })
        m.offer(set)
        return Triple(files, m, set)
    }

    @Test fun `offering a change writes nothing`() = runTest {
        val (files, m, _) = setup()
        assertEquals(original, files.files); assertTrue(files.writes.isEmpty())
        assertEquals(ChangeStatus.PENDING, m.entries.value.single().status)
    }

    @Test fun `apply writes all files and records history`() = runTest {
        val (files, m, set) = setup()
        val r = m.apply(set.id) as ApplyOutcome.Applied
        assertEquals(setOf("a.kt", "b.kt", "new.kt"), r.record.files.map { it.path }.toSet())
        assertEquals("ONE\n", files.files["a.kt"]); assertEquals("new\n", files.files["new.kt"])
        assertEquals(ChangeStatus.APPLIED, m.entry(set.id)!!.status); assertEquals(1, m.history.value.size)
        assertTrue(m.apply(set.id) is ApplyOutcome.Invalid) // not twice
    }

    @Test fun `apply only the selected files`() = runTest {
        val (files, m, set) = setup()
        m.apply(set.id, setOf("b.kt"))
        assertEquals("one\n", files.files["a.kt"]); assertEquals("TWO\n", files.files["b.kt"]); assertFalse("new.kt" in files.files)
        assertTrue(m.apply(set.id, emptySet()) is ApplyOutcome.Invalid || m.entry(set.id)!!.status == ChangeStatus.APPLIED)
    }

    @Test fun `a file edited after the suggestion makes apply refuse and write nothing`() = runTest {
        val (files, m, set) = setup()
        files.files["b.kt"] = "user edit\n"
        val r = m.apply(set.id) as ApplyOutcome.Stale
        assertEquals(listOf("b.kt"), r.paths)
        assertEquals("one\n", files.files["a.kt"]); assertEquals("user edit\n", files.files["b.kt"]); assertTrue(files.writes.isEmpty())
        assertEquals(ChangeStatus.PENDING, m.entry(set.id)!!.status)
    }

    @Test fun `a new file that appeared in the meantime is never overwritten`() = runTest {
        val (files, m, set) = setup()
        files.files["new.kt"] = "someone created this\n"
        assertEquals(listOf("new.kt"), (m.apply(set.id) as ApplyOutcome.Stale).paths)
        assertEquals("someone created this\n", files.files["new.kt"])
    }

    @Test fun `a failing write rolls back the files already written`() = runTest {
        val (files, m, set) = setup()
        files.failWritesTo = setOf("new.kt")
        val r = m.apply(set.id) as ApplyOutcome.Failed
        assertTrue(r.reason.contains("Earlier files were put back"))
        assertEquals(original, files.files); assertTrue(m.history.value.isEmpty()); assertEquals(ChangeStatus.PENDING, m.entry(set.id)!!.status)
    }

    @Test fun `reject, bring back and discard`() = runTest {
        val (files, m, set) = setup()
        assertTrue(m.reject(set.id)); assertEquals(ChangeStatus.REJECTED, m.entry(set.id)!!.status)
        assertTrue(m.bringBack(set.id)); assertFalse(m.bringBack(set.id))
        m.reject(set.id); m.discard(set.id); assertNull(m.entry(set.id))
        assertEquals(original, files.files) // rejecting never touches files
    }

    @Test fun `undo restores modified files and removes created ones`() = runTest {
        val (files, m, set) = setup()
        m.apply(set.id)
        assertTrue(m.undo(set.id) is UndoOutcome.Undone)
        assertEquals(original, files.files)
        assertTrue(m.history.value.single().undone); assertEquals(ChangeStatus.PENDING, m.entry(set.id)!!.status)
        assertEquals(UndoOutcome.NotFound, m.undo(set.id))
    }

    @Test fun `undo refuses when the user edited the file since, unless forced`() = runTest {
        val (files, m, set) = setup()
        m.apply(set.id)
        files.files["a.kt"] = "ONE plus my work\n"
        assertEquals(listOf("a.kt"), (m.undo(set.id) as UndoOutcome.Conflict).paths)
        assertEquals("ONE plus my work\n", files.files["a.kt"]); assertEquals("TWO\n", files.files["b.kt"])
        assertTrue(m.undo(set.id, force = true) is UndoOutcome.Undone)
        assertEquals(original, files.files)
    }

    @Test fun `history survives a restart through the store`() = runTest {
        val store = InMemoryChangeHistoryStore()
        val files = FakeProjectFiles(original)
        val set = ChangeResolver.fromProposal(EditProposal("s", listOf(RawChange("a.kt", "modify", "e", "ONE\n", emptyList()))), files, "T", 1L).changeSet!!
        val first = AiChangeManager("p1", files, store); first.offer(set); first.apply(set.id)
        val second = AiChangeManager("p1", files, store); second.load()
        assertEquals(1, second.history.value.size)
        assertTrue(second.undo(set.id) is UndoOutcome.Undone)
        assertEquals("one\n", files.files["a.kt"])
    }

    @Test fun `history is capped`() = runTest {
        val files = FakeProjectFiles(mapOf("a.kt" to "0\n")); val m = AiChangeManager("p", files, InMemoryChangeHistoryStore())
        for (i in 1..AiChangeManager.MAX_HISTORY + 5) {
            val set = ChangeResolver.fromProposal(EditProposal("s", listOf(RawChange("a.kt", "modify", "e", "$i\n", emptyList()))), files, "T$i", i.toLong()).changeSet!!
            m.offer(set); m.apply(set.id)
        }
        assertEquals(AiChangeManager.MAX_HISTORY, m.history.value.size)
    }
}
