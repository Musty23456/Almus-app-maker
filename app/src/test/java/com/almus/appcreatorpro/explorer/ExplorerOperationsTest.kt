package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.model.SourceFileEntry
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Wraps a repository and fails the Nth rename / delete, to prove that multi-file changes are rolled back. */
private class FlakyRepo(private val inner: SourceFileRepository) : SourceFileRepository {
    var failRenameAt = -1
    var failDeleteAt = -1
    private var renames = 0
    private var deletes = 0

    override suspend fun listFiles(projectId: String): List<SourceFileEntry> = inner.listFiles(projectId)
    override suspend fun readFile(projectId: String, path: String): String = inner.readFile(projectId, path)
    override suspend fun writeFile(projectId: String, path: String, content: String) = inner.writeFile(projectId, path, content)
    override suspend fun createFile(projectId: String, path: String, content: String) = inner.createFile(projectId, path, content)

    override suspend fun renameFile(projectId: String, fromPath: String, toPath: String) {
        renames++
        if (renames == failRenameAt) throw IOException("disk full")
        inner.renameFile(projectId, fromPath, toPath)
    }

    override suspend fun deleteFile(projectId: String, path: String) {
        deletes++
        if (deletes == failDeleteAt) throw IOException("disk error")
        inner.deleteFile(projectId, path)
    }
}

class ExplorerOperationsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FileProjectStore
    private lateinit var id: String
    private lateinit var ops: ExplorerOperations

    @Before
    fun setUp() = runTest {
        store = FileProjectStore(File(tmp.root, "projects"), ioDispatcher = Dispatchers.Unconfined)
        id = store.createProject("Demo", "", "", ProjectType.WEB).id
        ops = ExplorerOperations(id, store)
    }

    private suspend fun paths() = store.listFiles(id).map { it.path }.toSet()
    private fun filesDir() = File(tmp.root, "projects/$id/files")

    // ---------------------------------------------------------------- create

    @Test
    fun createFile_cleans_the_path_and_creates_an_empty_file() = runTest {
        val r = ops.createFile("  ./src//Util.kt ") as OpResult.Done
        assertEquals("src/Util.kt", r.created)
        assertEquals("", store.readFile(id, "src/Util.kt"))
    }

    @Test
    fun createFile_rejects_bad_and_existing_paths() = runTest {
        assertTrue(ops.createFile("../evil.kt") is OpResult.Failed)
        assertTrue(ops.createFile("") is OpResult.Failed)
        val dup = ops.createFile("index.html") as OpResult.Failed
        assertTrue(dup.message.contains("already exists"))
        store.createFile(id, "pkg/A.kt", "")
        val folder = ops.createFile("pkg") as OpResult.Failed
        assertTrue(folder.message.contains("folder"))
    }

    @Test
    fun createFolder_makes_a_gitkeep_and_rejects_existing_folders() = runTest {
        val r = ops.createFolder("src/utils/") as OpResult.Done
        assertEquals("src/utils/.gitkeep", r.created)
        assertTrue("src/utils/.gitkeep" in paths())
        assertTrue(ops.createFolder("src/utils") is OpResult.Failed)
        assertTrue(ops.createFolder("index.html") is OpResult.Failed)
        assertTrue(ops.createFolder("../up") is OpResult.Failed)
    }

    // ---------------------------------------------------------------- duplicate

    @Test
    fun duplicate_copies_the_content_next_to_the_original_and_never_overwrites() = runTest {
        val original = store.readFile(id, "style.css")
        val first = ops.duplicate("style.css") as OpResult.Done
        assertEquals("style copy.css", first.created)
        val second = ops.duplicate("style.css") as OpResult.Done
        assertEquals("style copy 2.css", second.created)
        assertEquals(original, store.readFile(id, "style copy.css"))
        assertEquals(original, store.readFile(id, "style.css"))
    }

    @Test
    fun duplicate_of_a_missing_or_unreadable_file_fails_cleanly() = runTest {
        assertTrue(ops.duplicate("ghost.txt") is OpResult.Failed)
        File(filesDir(), "pic.bin").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))
        val r = ops.duplicate("pic.bin") as OpResult.Failed
        assertTrue(r.message.contains("UTF-8"))
        assertFalse("pic copy.bin" in paths())
    }

    @Test
    fun duplicate_inside_a_folder_stays_in_that_folder() = runTest {
        store.createFile(id, "src/A.kt", "class A")
        val r = ops.duplicate("src/A.kt") as OpResult.Done
        assertEquals("src/A copy.kt", r.created)
        assertEquals("class A", store.readFile(id, "src/A copy.kt"))
    }

    // ---------------------------------------------------------------- rename

    @Test
    fun rename_moves_the_file_and_reports_the_move() = runTest {
        val r = ops.rename("script.js", "js/main.js") as OpResult.Done
        assertEquals(mapOf("script.js" to "js/main.js"), r.moved)
        assertTrue("js/main.js" in paths())
        assertFalse("script.js" in paths())
    }

    @Test
    fun rename_refuses_conflicts_and_bad_targets_and_changes_nothing() = runTest {
        val before = paths()
        assertTrue(ops.rename("script.js", "style.css") is OpResult.Failed)
        assertTrue(ops.rename("script.js", "script.js") is OpResult.Failed)
        assertTrue(ops.rename("script.js", "../x.js") is OpResult.Failed)
        assertTrue(ops.rename("ghost.js", "g.js") is OpResult.Failed)
        store.createFile(id, "dir/a.txt", "")
        assertTrue(ops.rename("script.js", "dir") is OpResult.Failed)
        assertEquals(before + "dir/a.txt", paths())
    }

    @Test
    fun renaming_an_important_file_needs_confirmation_first() = runTest {
        store.createFile(id, "build.gradle.kts", "plugins {}")
        val ask = ops.rename("build.gradle.kts", "build-old.gradle.kts")
        assertTrue(ask is OpResult.NeedsConfirmation)
        assertEquals(listOf("build.gradle.kts"), (ask as OpResult.NeedsConfirmation).protectedPaths)
        assertTrue("build.gradle.kts" in paths())
        val done = ops.rename("build.gradle.kts", "build-old.gradle.kts", confirmedImportant = true)
        assertTrue(done is OpResult.Done)
        assertTrue("build-old.gradle.kts" in paths())
    }

    @Test
    fun renameFolder_moves_every_file_with_its_content() = runTest {
        store.createFile(id, "src/a/A.kt", "A")
        store.createFile(id, "src/B.kt", "B")
        val r = ops.renameFolder("src", "lib/core") as OpResult.Done
        assertEquals(mapOf("src/B.kt" to "lib/core/B.kt", "src/a/A.kt" to "lib/core/a/A.kt"), r.moved)
        assertEquals("A", store.readFile(id, "lib/core/a/A.kt"))
        assertEquals("B", store.readFile(id, "lib/core/B.kt"))
        assertTrue(paths().none { it.startsWith("src/") })
    }

    @Test
    fun renameFolder_checks_everything_before_moving_anything() = runTest {
        store.createFile(id, "src/A.kt", "A")
        store.createFile(id, "src/B.kt", "B")
        store.createFile(id, "dst/B.kt", "other")
        val before = paths()
        val clash = ops.renameFolder("src", "dst") as OpResult.Failed
        assertTrue(clash.message.contains("already exists"))
        assertTrue(ops.renameFolder("src", "src/inner") is OpResult.Failed)
        assertTrue(ops.renameFolder("nothing", "x") is OpResult.Failed)
        assertTrue(ops.renameFolder("src", "../out") is OpResult.Failed)
        assertEquals(before, paths())
        assertEquals("other", store.readFile(id, "dst/B.kt"))
    }

    @Test
    fun renameFolder_with_a_file_in_the_way_fails() = runTest {
        store.createFile(id, "src/A.kt", "A")
        val r = ops.renameFolder("src", "index.html")
        assertTrue(r is OpResult.Failed)
        val r2 = ops.renameFolder("src", "index.html/inner")
        assertTrue(r2 is OpResult.Failed)
        assertTrue("src/A.kt" in paths())
    }

    @Test
    fun renameFolder_rolls_back_when_a_move_fails_halfway() = runTest {
        store.createFile(id, "src/A.kt", "A")
        store.createFile(id, "src/B.kt", "B")
        store.createFile(id, "src/C.kt", "C")
        val flaky = FlakyRepo(store).also { it.failRenameAt = 3 }
        val r = ExplorerOperations(id, flaky).renameFolder("src", "moved") as OpResult.Failed
        assertTrue(r.message.contains("Nothing was changed"))
        assertEquals(setOf("src/A.kt", "src/B.kt", "src/C.kt"), paths().filter { it.contains("/") }.toSet())
        assertEquals("A", store.readFile(id, "src/A.kt"))
        assertEquals("C", store.readFile(id, "src/C.kt"))
    }

    @Test
    fun renameFolder_with_important_files_asks_first() = runTest {
        store.createFile(id, "app/build.gradle.kts", "x")
        store.createFile(id, "app/Main.kt", "m")
        val ask = ops.renameFolder("app", "mobile")
        assertTrue(ask is OpResult.NeedsConfirmation)
        assertTrue("app/Main.kt" in paths())
        assertTrue(ops.renameFolder("app", "mobile", confirmedImportant = true) is OpResult.Done)
        assertTrue("mobile/build.gradle.kts" in paths())
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun planDelete_describes_what_would_go() = runTest {
        store.createFile(id, "app/build.gradle.kts", "x")
        store.createFile(id, "app/src/A.kt", "abc")
        val file = ops.planDelete("style.css", isFolder = false)!!
        assertEquals(listOf("style.css"), file.files)
        assertFalse(file.needsTypedConfirmation)
        val folder = ops.planDelete("app", isFolder = true)!!
        assertEquals(listOf("app/build.gradle.kts", "app/src/A.kt"), folder.files)
        assertEquals(listOf("app/build.gradle.kts"), folder.protectedFiles)
        assertTrue(folder.needsTypedConfirmation)
        assertEquals("app", folder.confirmationWord)
        assertNull(ops.planDelete("ghost.txt", isFolder = false))
        assertNull(ops.planDelete("ghost", isFolder = true))
    }

    @Test
    fun deleting_an_ordinary_file_needs_no_typing() = runTest {
        val plan = ops.planDelete("script.js", false)!!
        val r = ops.delete(plan, null) as OpResult.Done
        assertEquals(listOf("script.js"), r.deleted)
        assertFalse("script.js" in paths())
    }

    @Test
    fun an_important_file_is_deleted_only_after_its_name_is_typed() = runTest {
        store.createFile(id, "app/AndroidManifest.xml", "<manifest/>")
        val plan = ops.planDelete("app/AndroidManifest.xml", false)!!
        assertTrue(plan.needsTypedConfirmation)
        assertEquals("AndroidManifest.xml", plan.confirmationWord)

        assertTrue(ops.delete(plan, null) is OpResult.Failed)
        assertTrue(ops.delete(plan, "androidmanifest.xml") is OpResult.Failed)
        assertTrue(ops.delete(plan, "wrong") is OpResult.Failed)
        assertTrue("app/AndroidManifest.xml" in paths())

        assertTrue(ops.delete(plan, "  AndroidManifest.xml ") is OpResult.Done)
        assertFalse("app/AndroidManifest.xml" in paths())
    }

    @Test
    fun deleting_a_folder_removes_every_file_in_it() = runTest {
        store.createFile(id, "src/a/A.kt", "A")
        store.createFile(id, "src/B.kt", "B")
        val plan = ops.planDelete("src", true)!!
        val r = ops.delete(plan, null) as OpResult.Done
        assertEquals(listOf("src/B.kt", "src/a/A.kt"), r.deleted.sorted())
        assertTrue(paths().none { it.startsWith("src/") })
        assertFalse(File(filesDir(), "src").exists())
    }

    @Test
    fun a_folder_with_an_important_file_needs_the_folder_name_typed() = runTest {
        store.createFile(id, "app/build.gradle.kts", "x")
        store.createFile(id, "app/A.kt", "A")
        val plan = ops.planDelete("app", true)!!
        assertTrue(ops.delete(plan, "build.gradle.kts") is OpResult.Failed)
        assertEquals(2, paths().count { it.startsWith("app/") })
        assertTrue(ops.delete(plan, "app") is OpResult.Done)
        assertTrue(paths().none { it.startsWith("app/") })
    }

    @Test
    fun a_failed_folder_delete_puts_back_what_was_already_deleted() = runTest {
        store.createFile(id, "src/A.kt", "A")
        store.createFile(id, "src/B.kt", "B")
        store.createFile(id, "src/C.kt", "C")
        val flaky = FlakyRepo(store).also { it.failDeleteAt = 3 }
        val flakyOps = ExplorerOperations(id, flaky)
        val plan = flakyOps.planDelete("src", true)!!
        val r = flakyOps.delete(plan, null) as OpResult.Failed
        assertTrue(r.message.contains("Nothing was deleted"))
        assertEquals("A", store.readFile(id, "src/A.kt"))
        assertEquals("B", store.readFile(id, "src/B.kt"))
        assertEquals("C", store.readFile(id, "src/C.kt"))
    }

    @Test
    fun a_folder_with_an_unreadable_file_is_not_deleted_at_all() = runTest {
        store.createFile(id, "assets/a.txt", "a")
        File(filesDir(), "assets/pic.bin").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))
        val plan = ops.planDelete("assets", true)!!
        val r = ops.delete(plan, null) as OpResult.Failed
        assertTrue(r.message.contains("nothing was deleted"))
        assertTrue("assets/a.txt" in paths())
        assertTrue(File(filesDir(), "assets/pic.bin").exists())
    }

    @Test
    fun deleting_something_that_vanished_reports_it() = runTest {
        val plan = ops.planDelete("script.js", false)!!
        store.deleteFile(id, "script.js")
        assertTrue(ops.delete(plan, null) is OpResult.Failed)
    }

    // ---------------------------------------------------------------- workspace containment

    @Test
    fun no_operation_can_reach_a_file_outside_the_project() = runTest {
        val outside = File(tmp.root, "projects/$id/secret.txt").also { it.writeText("keep me") }
        val sibling = File(tmp.root, "outside.txt").also { it.writeText("keep me too") }
        val attempts = listOf(
            ops.createFile("../secret.txt"),
            ops.createFolder("../newdir"),
            ops.duplicate("../secret.txt"),
            ops.rename("../secret.txt", "stolen.txt"),
            ops.rename("script.js", "../escaped.js"),
            ops.renameFolder("../..", "x"),
            ops.renameFolder("src", "../../elsewhere"),
        )
        for (a in attempts) assertTrue(a.toString(), a is OpResult.Failed)

        val badPlan = DeletePlan("../secret.txt", false, listOf("../secret.txt"), 0, emptyList())
        assertTrue(ops.delete(badPlan, null) is OpResult.Failed)

        assertEquals("keep me", outside.readText())
        assertEquals("keep me too", sibling.readText())
        assertFalse(File(tmp.root, "projects/$id/escaped.js").exists())
        assertNotNull(paths().firstOrNull { it == "script.js" })
    }

    @Test
    fun a_path_that_starts_with_a_slash_is_treated_as_relative_to_the_project() = runTest {
        val r = ops.rename("script.js", "/abs/path.js") as OpResult.Done
        assertEquals(mapOf("script.js" to "abs/path.js"), r.moved)
        assertTrue(File(filesDir(), "abs/path.js").exists())
        assertFalse(File("/abs/path.js").exists())
    }
}
