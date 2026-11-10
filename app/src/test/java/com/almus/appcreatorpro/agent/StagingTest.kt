package com.almus.appcreatorpro.agent

import com.almus.appcreatorpro.ai.ChangeKind
import com.almus.appcreatorpro.ai.FakeProjectFiles
import com.almus.appcreatorpro.ai.RawChange
import com.almus.appcreatorpro.debug.AiPatchEdit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StagingTest {
    private fun create(path: String, text: String) = RawChange(path, "create", "", text, emptyList())
    private fun edit(path: String, find: String, replace: String) = RawChange(path, "modify", "", null, listOf(AiPatchEdit(find, replace)))

    // ---- the overlay

    @Test fun `writes go only to the overlay, never to the real project`() = runTest {
        val real = FakeProjectFiles(sampleProject())
        val staged = StagedProjectFiles(real)
        staged.write(FOO_PATH, fooClass())
        staged.write(MAIN_PATH, "package com.example.notes\n\nclass MainActivity\n")
        assertTrue(real.writes.isEmpty())
        assertFalse(FOO_PATH in real.files)
        assertEquals(MAIN_TEXT, real.files[MAIN_PATH])
        assertEquals(fooClass(), staged.read(FOO_PATH))
        assertTrue(FOO_PATH in staged.list())
        assertTrue(staged.exists(FOO_PATH))
    }

    @Test fun `the agent can never delete`() = runTest {
        val real = FakeProjectFiles(sampleProject())
        val staged = StagedProjectFiles(real)
        try { staged.delete(MAIN_PATH); fail("delete must throw") } catch (e: UnsupportedOperationException) { /* expected */ }
        assertTrue(MAIN_PATH in real.files)
        assertFalse(AgentPermissions().canDelete)
    }

    @Test fun `discard removes a file from the result`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        staged.write(FOO_PATH, fooClass())
        staged.discard(FOO_PATH)
        assertTrue(staged.stagedPaths().isEmpty())
        assertFalse(staged.exists(FOO_PATH))
    }

    @Test fun `the change set separates new and changed files and skips unchanged ones`() = runTest {
        val real = FakeProjectFiles(sampleProject())
        val staged = StagedProjectFiles(real)
        staged.write(FOO_PATH, fooClass())
        staged.write(MAIN_PATH, MAIN_TEXT.replace("Notes", "My notes"))
        staged.write("settings.gradle.kts", real.files["settings.gradle.kts"]!!)
        val r = staged.buildChangeSet("run1", "Agent: notes", "summary", 9L)
        val set = r.changeSet!!
        assertEquals(setOf(FOO_PATH, MAIN_PATH), set.paths.toSet())
        assertEquals("Agent: notes", set.title)
        assertEquals(ChangeKind.CREATE, set.changes.first { it.path == FOO_PATH }.kind)
        assertEquals(ChangeKind.MODIFY, set.changes.first { it.path == MAIN_PATH }.kind)
        assertTrue(real.writes.isEmpty())
    }

    @Test fun `a file that changed in the real project after staging is reported stale`() = runTest {
        val real = FakeProjectFiles(sampleProject())
        val staged = StagedProjectFiles(real)
        staged.write(MAIN_PATH, MAIN_TEXT.replace("Notes", "Agent"))
        staged.write(FOO_PATH, fooClass())
        assertTrue(staged.staleAgainstBase().isEmpty())
        real.files[MAIN_PATH] = "package com.example.notes\n\nclass MainActivity { /* user edit */ }\n"
        assertEquals(listOf(MAIN_PATH), staged.staleAgainstBase())
        real.files[FOO_PATH] = "the user created this meanwhile"
        assertEquals(setOf(MAIN_PATH, FOO_PATH), staged.staleAgainstBase().toSet())
    }

    @Test fun `snapshot and restore bring the overlay back exactly`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        staged.write(FOO_PATH, "one")
        val snap = staged.snapshot()
        staged.write(BAR_PATH, "two")
        staged.write(FOO_PATH, "changed")
        staged.restore(snap)
        assertEquals(listOf(FOO_PATH), staged.stagedPaths())
        assertEquals("one", staged.stagedText(FOO_PATH))
    }

    // ---- the guard and the applier

    @Test fun `guard refuses unsafe paths, empty text, binary data, huge files and new secrets`() {
        val p = AgentPermissions(maxFileBytes = 100)
        assertNotNull(AgentGuard.checkPath("../x.kt"))
        assertNotNull(AgentGuard.checkPath(".github/workflows/build.yml"))
        assertNull(AgentGuard.checkPath(FOO_PATH))
        assertNotNull(AgentGuard.checkNewText(null, "  \n", p))
        assertNotNull(AgentGuard.checkNewText(null, "a\u0000b", p))
        assertNotNull(AgentGuard.checkNewText(null, "x".repeat(200), p))
        assertNotNull(AgentGuard.checkNewText(null, "val key = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\"", AgentPermissions()))
        assertNull(AgentGuard.checkNewText(null, "val ok = 1", p))
    }

    @Test fun `applier stages new files and edits to files the model was shown`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        val r = StagingApplier(staged, AgentPermissions()).apply(
            listOf(create(FOO_PATH, fooClass()), edit(MAIN_PATH, "\"Notes\"", "\"Agent notes\"")), readable = setOf(MAIN_PATH), truncated = emptySet(), taskId = "t1",
        )
        assertEquals(setOf(FOO_PATH, MAIN_PATH), r.applied.toSet())
        assertTrue(r.rejected.isEmpty())
        assertTrue(staged.stagedText(MAIN_PATH)!!.contains("Agent notes"))
    }

    @Test fun `applier refuses to overwrite an existing file the model was not shown`() = runTest {
        val real = FakeProjectFiles(sampleProject())
        val staged = StagedProjectFiles(real)
        val r = StagingApplier(staged, AgentPermissions()).apply(listOf(RawChange(MAIN_PATH, "modify", "", "package x\n\nclass Y\n", emptyList())), emptySet(), emptySet(), "t1")
        assertTrue(r.applied.isEmpty())
        assertTrue(r.rejected.single().reason.contains("not shown"))
        assertEquals(MAIN_TEXT, real.files[MAIN_PATH])
    }

    @Test fun `a truncated file can only be changed with edits, not replaced`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        val applier = StagingApplier(staged, AgentPermissions())
        val whole = applier.apply(listOf(RawChange(MAIN_PATH, "modify", "", "package x\n", emptyList())), setOf(MAIN_PATH), setOf(MAIN_PATH), null)
        assertTrue(whole.applied.isEmpty())
        val small = applier.apply(listOf(edit(MAIN_PATH, "\"Notes\"", "\"N\"")), setOf(MAIN_PATH), setOf(MAIN_PATH), null)
        assertEquals(listOf(MAIN_PATH), small.applied)
    }

    @Test fun `applier rejects deletes, duplicates, secrets, no-ops and failed edits`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        val r = StagingApplier(staged, AgentPermissions()).apply(
            listOf(
                RawChange(MAIN_PATH, "delete", "", null, emptyList()),
                create(FOO_PATH, fooClass()),
                create(FOO_PATH.uppercase().replace("APP/SRC", "app/src"), fooClass("Two")),
                create(BAR_PATH, "val key = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\""),
                RawChange(MAIN_PATH, "modify", "", MAIN_TEXT, emptyList()),
                edit(MAIN_PATH, "text that is not in the file", "x"),
                create("../escape.kt", "x"),
            ),
            readable = setOf(MAIN_PATH), truncated = emptySet(), taskId = null,
        )
        assertEquals(listOf(FOO_PATH), r.applied)
        assertEquals(6, r.rejected.size)
        assertTrue(r.rejected.any { it.reason.contains("never deletes") })
        assertTrue(r.rejected.any { it.reason.contains("secret") })
        assertTrue(r.rejected.any { it.reason.contains("Nothing changes") })
    }

    @Test fun `applier enforces the file count and total size limits`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        val few = StagingApplier(staged, AgentPermissions(maxStagedFiles = 2))
        val r = few.apply((1..4).map { create("app/src/main/java/com/example/notes/C$it.kt", fooClass("C$it")) }, emptySet(), emptySet(), null)
        assertEquals(2, r.applied.size)
        assertEquals(2, r.rejected.size)
        val small = StagingApplier(StagedProjectFiles(FakeProjectFiles(sampleProject())), AgentPermissions(maxStagedBytes = 80))
        assertEquals(1, small.apply(listOf(create(FOO_PATH, fooClass()), create(BAR_PATH, fooClass("Bar"))), emptySet(), emptySet(), null).rejected.size)
    }

    // ---- the tools

    private fun toolbox(files: FakeProjectFiles, perms: AgentPermissions = AgentPermissions()) = AgentToolbox(StagedProjectFiles(files), perms, mutableSetOf(), mutableSetOf())

    @Test fun `read_file returns the file with secrets removed and records it as read`() = runTest {
        val files = FakeProjectFiles(sampleProject() + (FOO_PATH to "val key = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\"\nclass Foo\n"))
        val tb = toolbox(files)
        val out = tb.run(listOf(ToolCall("read_file", mapOf("path" to FOO_PATH)))).single()
        assertFalse(out.error)
        assertTrue(out.text.contains("class Foo"))
        assertFalse(out.text.contains("AIzaSyA1234567890"))
        assertTrue(FOO_PATH in tb.readable)
    }

    @Test fun `tools refuse secret files, missing files, unknown tools and tools outside the allowed set`() = runTest {
        val files = FakeProjectFiles(sampleProject() + ("keystore.jks" to "x") + (".env" to "TOKEN=1") + ("local.properties" to "sdk.dir=/x"))
        val tb = toolbox(files)
        val out = tb.run(
            listOf(
                ToolCall("read_file", mapOf("path" to ".env")), ToolCall("read_file", mapOf("path" to "local.properties")),
                ToolCall("read_file", mapOf("path" to "nope.kt")), ToolCall("delete_file", mapOf("path" to MAIN_PATH)), ToolCall("read_file", emptyMap()),
            ),
        )
        assertTrue(out.all { it.error })
        assertTrue(MAIN_PATH in files.files)
        val restricted = toolbox(files, AgentPermissions(allowedTools = setOf("list_files")))
        assertTrue(restricted.run(listOf(ToolCall("read_file", mapOf("path" to MAIN_PATH)))).single().error)
    }

    @Test fun `read_file is limited per task and search and list_files hide secret files`() = runTest {
        val files = FakeProjectFiles(sampleProject() + (".env" to "TOKEN=hello") + (FOO_PATH to "class Foo // hello\n"))
        val tb = toolbox(files, AgentPermissions(maxFilesReadPerTask = 1))
        val reads = tb.run(listOf(ToolCall("read_file", mapOf("path" to MAIN_PATH)), ToolCall("read_file", mapOf("path" to FOO_PATH))))
        assertFalse(reads[0].error)
        assertTrue(reads[1].error)
        val search = toolbox(files).run(listOf(ToolCall("search", mapOf("query" to "hello")))).single()
        assertTrue(search.text.contains(FOO_PATH))
        assertFalse(search.text.contains(".env"))
        val list = toolbox(files).run(listOf(ToolCall("list_files", emptyMap()))).single()
        assertTrue(list.text.contains(MAIN_PATH))
        assertFalse(list.text.contains(".env"))
    }

    @Test fun `tools see files staged by earlier tasks`() = runTest {
        val staged = StagedProjectFiles(FakeProjectFiles(sampleProject()))
        staged.write(FOO_PATH, fooClass())
        val out = AgentToolbox(staged, AgentPermissions(), mutableSetOf(), mutableSetOf()).run(listOf(ToolCall("read_file", mapOf("path" to FOO_PATH)))).single()
        assertFalse(out.error)
        assertTrue(out.text.contains("class Foo"))
    }
}
