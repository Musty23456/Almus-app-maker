package com.almus.appcreatorpro.data

import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.domain.error.StorageException
import com.almus.appcreatorpro.domain.error.StorageException.Kind
import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.repository.SourceFileRepository
import com.almus.appcreatorpro.domain.template.ProjectTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProjectStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private var time = 1_000L
    private var counter = 0

    private fun store(root: File = File(tmp.root, "projects")) = FileProjectStore(
        rootDir = root,
        clock = { time++ },
        idGenerator = { "id${counter++}" },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private suspend inline fun <reified E : Throwable> expectThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw e
        }
        fail("Expected ${E::class.simpleName}")
        throw IllegalStateException()
    }

    // ------------------------------------------------------------ project creation / loading

    @Test
    fun create_saves_project_with_type_and_dates() = runTest {
        val s = store()
        val p = s.createProject("My App", "com.example.myapp", "desc", ProjectType.ANDROID_JAVA)

        assertEquals("id0", p.id)
        assertEquals(ProjectType.ANDROID_JAVA, p.type)
        assertEquals("desc", p.description)
        assertEquals(p.createdAtMillis, p.updatedAtMillis)
        assertEquals(listOf(p), s.getProjects())
        assertEquals(p, s.getProject("id0"))
    }

    @Test
    fun create_writes_starter_files_for_each_type() = runTest {
        val s = store()
        val kotlin = s.createProject("Kot", "com.example.kot", "", ProjectType.ANDROID_KOTLIN)
        val java = s.createProject("Jav", "com.example.jav", "", ProjectType.ANDROID_JAVA)
        val web = s.createProject("Web", "", "", ProjectType.WEB)

        val kotlinFiles = s.listFiles(kotlin.id).map { it.path }
        assertTrue("app/src/main/java/com/example/kot/MainActivity.kt" in kotlinFiles)
        assertTrue("settings.gradle.kts" in kotlinFiles)
        assertTrue("app/src/main/java/com/example/jav/MainActivity.java" in s.listFiles(java.id).map { it.path })
        assertEquals(listOf("index.html", "README.md", "script.js", "style.css"), s.listFiles(web.id).map { it.path })
        assertEquals("", web.packageName)
    }

    @Test
    fun default_open_file_exists_in_new_project() = runTest {
        val s = store()
        for (type in ProjectType.entries) {
            val pkg = if (type.requiresPackage) "com.example.t${type.ordinal}" else ""
            val p = s.createProject("Proj ${type.ordinal}", pkg, "", type)
            val expected = ProjectTemplates.defaultOpenFile(type, pkg)
            assertTrue("$expected missing for $type", expected in s.listFiles(p.id).map { it.path })
        }
    }

    @Test
    fun projects_survive_recreating_the_store() = runTest {
        val first = store()
        val p = first.createProject("Persistent", "com.example.persistent", "keep me", ProjectType.ANDROID_KOTLIN)
        first.writeFile(p.id, "notes.txt", "héllo wörld ✓")
        first.renameProject(p.id, "Persistent 2")

        val second = store() // simulates restarting the app
        val loaded = second.getProjects()
        assertEquals(1, loaded.size)
        assertEquals("Persistent 2", loaded[0].name)
        assertEquals("keep me", loaded[0].description)
        assertEquals(ProjectType.ANDROID_KOTLIN, loaded[0].type)
        assertEquals(p.createdAtMillis, loaded[0].createdAtMillis)
        assertEquals("héllo wörld ✓", second.readFile(p.id, "notes.txt"))
        assertTrue(second.observeLoadIssues().first().isEmpty())
    }

    @Test
    fun observe_emits_saved_projects_newest_first() = runTest {
        val first = store()
        first.createProject("Older", "com.example.older", "")
        first.createProject("Newer", "com.example.newer", "")

        val names = store().observeProjects().first().map { it.name }
        assertEquals(listOf("Newer", "Older"), names)
    }

    @Test
    fun special_characters_in_description_round_trip() = runTest {
        val desc = "line1\nline2 = value: #not-a-comment \\ backslash ünï"
        val first = store()
        first.createProject("Chars", "com.example.chars", desc)
        assertEquals(desc, store().getProjects().single().description)
    }

    // ------------------------------------------------------------ validation / errors

    @Test
    fun invalid_project_names_are_rejected_and_nothing_is_created() = runTest {
        val s = store()
        for (bad in listOf("", "  ", "ab", "-start", "bad/name", "a".repeat(41))) {
            expectThrows<ValidationException> { s.createProject(bad, "com.example.x", "") }
        }
        assertTrue(s.getProjects().isEmpty())
        assertFalse(File(tmp.root, "projects").listFiles().orEmpty().any { it.isDirectory })
    }

    @Test
    fun duplicate_name_is_rejected() = runTest {
        val s = store()
        s.createProject("Same Name", "com.example.one", "")
        expectThrows<ValidationException> { s.createProject("same name", "com.example.two", "") }
    }

    @Test
    fun invalid_package_is_rejected_for_android_but_not_for_web() = runTest {
        val s = store()
        expectThrows<ValidationException> { s.createProject("Android", "nope", "", ProjectType.ANDROID_KOTLIN) }
        assertNotNull(s.createProject("A Website", "", "", ProjectType.WEB))
    }

    @Test
    fun storage_failure_is_reported_as_StorageException_and_leaves_no_project() = runTest {
        val blocker = File(tmp.root, "blocked")
        blocker.writeText("I am a file, not a folder")
        val s = store(root = blocker) // root directory cannot be created

        val error = expectThrows<StorageException> { s.createProject("Fails", "com.example.fails", "") }
        assertEquals(Kind.IO, error.kind)
        assertTrue(s.getProjects().isEmpty())
    }

    @Test
    fun failed_create_cleans_up_its_folder() = runTest {
        val root = File(tmp.root, "projects")
        val s = FileProjectStore(root, clock = { 1L }, idGenerator = { "same" }, ioDispatcher = Dispatchers.Unconfined)
        s.createProject("First", "com.example.first", "")
        // second create with the same id fails (folder exists) and must not delete the first project
        expectThrows<StorageException> { s.createProject("Second", "com.example.second", "") }
        assertEquals(1, s.getProjects().size)
        assertEquals("First", s.getProjects()[0].name)
        assertTrue(s.listFiles("same").isNotEmpty())
    }

    @Test
    fun corrupted_and_incomplete_projects_are_skipped_and_reported() = runTest {
        val root = File(tmp.root, "projects")
        val good = store(root).createProject("Good One", "com.example.good", "")

        File(root, "broken1").apply { mkdirs() }                                   // no metadata
        File(root, "broken2/project.properties").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0, 1, 2, 3, -1, -2)) }
        File(root, "broken3/project.properties").apply { parentFile.mkdirs(); writeText("id=broken3\nname=X\ntype=martian\ncreatedAt=1\nupdatedAt=1\n") }
        File(root, "broken4/project.properties").apply { parentFile.mkdirs(); writeText("id=other\nname=X\ntype=web\ncreatedAt=1\nupdatedAt=1\n") }
        File(root, "broken5/project.properties").apply { parentFile.mkdirs(); writeText("id=broken5\nname=X\ntype=web\ncreatedAt=notanumber\nupdatedAt=1\n") }
        File(root, "stray.txt").writeText("not a project")

        val s = store(root)
        assertEquals(listOf(good.id), s.getProjects().map { it.id })
        assertEquals(5, s.observeLoadIssues().first().size)
    }

    @Test
    fun deleted_source_folder_is_handled_gracefully() = runTest {
        val s = store()
        val p = s.createProject("Gone Files", "com.example.gone", "")
        File(tmp.root, "projects/${p.id}/files").deleteRecursively()

        assertTrue(s.listFiles(p.id).isEmpty())
        assertEquals(Kind.NOT_FOUND, expectThrows<StorageException> { s.readFile(p.id, "README.md") }.kind)
    }

    // ------------------------------------------------------------ rename / delete

    @Test
    fun rename_updates_name_and_keeps_files_and_creation_date() = runTest {
        val s = store()
        val p = s.createProject("Old Name", "com.example.old", "")
        val renamed = s.renameProject(p.id, "  New Name  ")

        assertEquals("New Name", renamed.name)
        assertEquals(p.createdAtMillis, renamed.createdAtMillis)
        assertTrue(renamed.updatedAtMillis > p.updatedAtMillis)
        assertTrue(s.listFiles(p.id).isNotEmpty())
        assertEquals("New Name", store().getProject(p.id)?.name)
    }

    @Test
    fun rename_validates_and_allows_keeping_the_same_name() = runTest {
        val s = store()
        val a = s.createProject("Alpha", "com.example.alpha", "")
        s.createProject("Beta", "com.example.beta", "")

        expectThrows<ValidationException> { s.renameProject(a.id, "beta") }
        expectThrows<ValidationException> { s.renameProject(a.id, "x") }
        assertEquals("Alpha", s.renameProject(a.id, "Alpha").name)
        assertEquals(Kind.NOT_FOUND, expectThrows<StorageException> { s.renameProject("nope", "Whatever") }.kind)
    }

    @Test
    fun delete_removes_project_and_files_from_disk() = runTest {
        val s = store()
        val p = s.createProject("Doomed", "com.example.doomed", "")
        s.deleteProject(p.id)

        assertTrue(s.getProjects().isEmpty())
        assertFalse(File(tmp.root, "projects/${p.id}").exists())
        assertTrue(store().getProjects().isEmpty())
    }

    @Test
    fun delete_unknown_or_unsafe_id_changes_nothing() = runTest {
        val s = store()
        s.createProject("Stays", "com.example.stays", "")
        s.deleteProject("missing")
        s.deleteProject("../projects")
        s.deleteProject("")
        assertEquals(1, s.getProjects().size)
        assertTrue(File(tmp.root, "projects").isDirectory)
    }

    // ------------------------------------------------------------ file operations

    private suspend fun webProject(s: FileProjectStore) = s.createProject("Site", "", "", ProjectType.WEB).id

    @Test
    fun write_then_read_round_trips_and_overwrites() = runTest {
        val s = store()
        val id = webProject(s)
        s.writeFile(id, "index.html", "<h1>v1</h1>")
        s.writeFile(id, "index.html", "<h1>v2</h1>")
        assertEquals("<h1>v2</h1>", s.readFile(id, "index.html"))
    }

    @Test
    fun saving_a_file_updates_the_project_modified_time() = runTest {
        val s = store()
        val id = webProject(s)
        val before = s.getProject(id)!!.updatedAtMillis
        s.writeFile(id, "script.js", "// changed")
        assertTrue(s.getProject(id)!!.updatedAtMillis > before)
    }

    @Test
    fun create_file_in_new_folders_and_listing_is_sorted() = runTest {
        val s = store()
        val id = webProject(s)
        s.createFile(id, "js/lib/util.js", "x")
        val paths = s.listFiles(id).map { it.path }
        assertTrue("js/lib/util.js" in paths)
        assertEquals(paths.sortedBy { it.lowercase() }, paths)
    }

    @Test
    fun create_file_rejects_existing_and_file_in_parent_path() = runTest {
        val s = store()
        val id = webProject(s)
        assertEquals(Kind.ALREADY_EXISTS, expectThrows<StorageException> { s.createFile(id, "index.html") }.kind)
        assertEquals(Kind.ALREADY_EXISTS, expectThrows<StorageException> { s.createFile(id, "index.html/inner.txt") }.kind)
    }

    @Test
    fun rename_file_moves_content_and_can_move_into_folders() = runTest {
        val s = store()
        val id = webProject(s)
        s.writeFile(id, "script.js", "console.log(1)")
        s.renameFile(id, "script.js", "js/main.js")

        val paths = s.listFiles(id).map { it.path }
        assertFalse("script.js" in paths)
        assertEquals("console.log(1)", s.readFile(id, "js/main.js"))
    }

    @Test
    fun rename_file_errors() = runTest {
        val s = store()
        val id = webProject(s)
        assertEquals(Kind.NOT_FOUND, expectThrows<StorageException> { s.renameFile(id, "ghost.txt", "x.txt") }.kind)
        assertEquals(Kind.ALREADY_EXISTS, expectThrows<StorageException> { s.renameFile(id, "script.js", "style.css") }.kind)
        assertEquals(Kind.INVALID_NAME, expectThrows<StorageException> { s.renameFile(id, "script.js", "../evil.js") }.kind)
        assertTrue("script.js" in s.listFiles(id).map { it.path })
    }

    @Test
    fun delete_file_removes_it_and_prunes_empty_folders() = runTest {
        val s = store()
        val id = webProject(s)
        s.createFile(id, "a/b/c.txt", "x")
        s.deleteFile(id, "a/b/c.txt")

        assertFalse(s.listFiles(id).any { it.path.startsWith("a/") })
        assertFalse(File(tmp.root, "projects/$id/files/a").exists())
        assertEquals(Kind.NOT_FOUND, expectThrows<StorageException> { s.deleteFile(id, "a/b/c.txt") }.kind)
    }

    @Test
    fun invalid_and_unsafe_paths_are_rejected() = runTest {
        val s = store()
        val id = webProject(s)
        for (bad in listOf("", "/etc/passwd", "../x.txt", "a/../../x.txt", "a//b.txt", "bad*name.txt", "dir/", "x.almus-tmp")) {
            val e = expectThrows<StorageException> { s.createFile(id, bad) }
            assertEquals("path '$bad'", Kind.INVALID_NAME, e.kind)
        }
        assertFalse(File(tmp.root, "x.txt").exists())
    }

    @Test
    fun file_operations_on_unknown_project_fail_with_not_found() = runTest {
        val s = store()
        assertEquals(Kind.NOT_FOUND, expectThrows<StorageException> { s.listFiles("nope") }.kind)
        assertEquals(Kind.NOT_FOUND, expectThrows<StorageException> { s.readFile("../x", "a.txt") }.kind)
    }

    @Test
    fun oversized_files_are_rejected_on_write_and_on_read() = runTest {
        val s = store()
        val id = webProject(s)
        val big = "a".repeat(SourceFileRepository.MAX_FILE_BYTES + 1)
        assertEquals(Kind.TOO_LARGE, expectThrows<StorageException> { s.writeFile(id, "big.txt", big) }.kind)

        File(tmp.root, "projects/$id/files/huge.txt").writeText(big)   // placed on disk behind the app's back
        assertEquals(Kind.TOO_LARGE, expectThrows<StorageException> { s.readFile(id, "huge.txt") }.kind)
    }

    @Test
    fun binary_file_is_reported_as_not_text() = runTest {
        val s = store()
        val id = webProject(s)
        File(tmp.root, "projects/$id/files/image.bin").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0xFF.toByte(), 0xFE.toByte()))
        assertEquals(Kind.CORRUPTED, expectThrows<StorageException> { s.readFile(id, "image.bin") }.kind)
    }

    @Test
    fun external_deletion_of_a_file_is_reported_not_crashed() = runTest {
        val s = store()
        val id = webProject(s)
        File(tmp.root, "projects/$id/files/style.css").delete()
        assertEquals(Kind.NOT_FOUND, expectThrows<StorageException> { s.readFile(id, "style.css") }.kind)
    }

    @Test
    fun write_to_a_folder_path_fails_cleanly() = runTest {
        val s = store()
        val id = webProject(s)
        s.createFile(id, "dir/file.txt", "x")
        assertEquals(Kind.ALREADY_EXISTS, expectThrows<StorageException> { s.writeFile(id, "dir", "oops") }.kind)
    }

    @Test
    fun no_temporary_files_remain_after_saving() = runTest {
        val s = store()
        val id = webProject(s)
        s.writeFile(id, "index.html", "x")
        val leftovers = File(tmp.root, "projects/$id").walkTopDown().filter { it.name.endsWith(".almus-tmp") }.toList()
        assertTrue(leftovers.isEmpty())
        assertNull(s.getProject("zzz"))
    }
}
