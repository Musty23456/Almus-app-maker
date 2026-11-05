package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.domain.model.Project
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.transfer.BackupKind
import com.almus.appcreatorpro.domain.transfer.ImportSourceKind
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

class ExportBackupRestoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Imports the demo Android project (with a png and a jar) and returns it. */
    private suspend fun importDemo(env: TransferEnv, name: String = "Demo App"): Project {
        val bytes = zipBytes(*androidKotlinFiles())
        val staged = env.importer.stageZip(ByteArrayInputStream(bytes), bytes.size.toLong(), "Demo.zip", ImportSourceKind.ZIP_FILE)
        return env.importer.commit(staged, name)
    }

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private suspend fun export(env: TransferEnv, project: Project): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        env.archiver.writeZip(project.id, out)
        return readZip(out.toByteArray())
    }

    // ---------------------------------------------------------------- export

    @Test
    fun export_contains_every_file_with_original_structure_and_bytes() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val zip = export(env, project)
        val original = androidKotlinFiles().associate { it.first to it.second }
        assertEquals(original.keys, zip.keys)
        for ((path, data) in original) assertArrayEquals("content of $path", data, zip[path])
    }

    @Test
    fun export_includes_files_edited_in_the_editor() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        env.store.writeFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt", "changed")
        env.store.createFile(project.id, "docs/NOTES.txt", "new file")
        val zip = export(env, project)
        assertEquals("changed", String(zip["app/src/main/java/com/demo/app/MainActivity.kt"]!!))
        assertEquals("new file", String(zip["docs/NOTES.txt"]!!))
    }

    @Test
    fun export_entries_never_escape_the_archive_root() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        for (name in export(env, project).keys) {
            assertFalse(name.startsWith("/"))
            assertFalse(name.split('/').contains(".."))
            assertFalse(name.contains("\\"))
        }
    }

    @Test
    fun export_reports_progress_up_to_the_total() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val seen = ArrayList<TransferProgress>()
        val summary = env.archiver.writeZip(project.id, ByteArrayOutputStream()) { seen += it }
        assertEquals(9, summary.fileCount)
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.phase == TransferProgress.Phase.WRITING })
        assertEquals(1f, seen.last().fraction!!, 0.0001f)
    }

    @Test
    fun export_then_import_round_trips_a_project() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val out = ByteArrayOutputStream()
        env.archiver.writeZip(project.id, out)
        val bytes = out.toByteArray()
        val staged = env.importer.stageZip(ByteArrayInputStream(bytes), bytes.size.toLong(), "Demo-export.zip", ImportSourceKind.ZIP_FILE)
        assertEquals(ProjectType.ANDROID_KOTLIN, staged.preview.type)
        assertEquals(2, staged.preview.preservedFiles)
        val copy = env.importer.commit(staged, "Demo Copy")
        assertEquals(env.store.listFiles(project.id).map { it.path }, env.store.listFiles(copy.id).map { it.path })
    }

    @Test
    fun export_of_unknown_project_fails_cleanly() = runTest {
        val env = TransferEnv(tmp)
        try {
            env.archiver.writeZip("nope", ByteArrayOutputStream())
            fail("expected NOT_FOUND")
        } catch (e: TransferException) {
            assertEquals(Kind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun export_to_a_broken_destination_reports_a_storage_error() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
        }
        try {
            env.archiver.writeZip(project.id, broken)
            fail("expected STORAGE")
        } catch (e: TransferException) {
            assertEquals(Kind.STORAGE, e.kind)
        }
    }

    // ---------------------------------------------------------------- backups

    @Test
    fun backups_are_listed_newest_first_and_hold_the_project_state_at_that_time() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val first = env.backups.createBackup(project.id, "v1")
        env.store.writeFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt", "version two")
        val second = env.backups.createBackup(project.id, "v2")

        val list = env.backups.listBackups(project.id)
        assertEquals(listOf(second.id, first.id), list.map { it.id })
        assertEquals(listOf("v2", "v1"), list.map { it.label })
        assertEquals(9, first.fileCount)
        assertEquals(BackupKind.MANUAL, first.kind)
        assertEquals(2, env.backups.listBackups(null).size)
        assertTrue(env.stagingLeftovers().isEmpty())
    }

    @Test
    fun restore_as_new_never_touches_the_original() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val backup = env.backups.createBackup(project.id)
        env.store.writeFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt", "edited after backup")

        val restored = env.backups.restoreAsNew(backup.id)
        assertNotEquals(project.id, restored.id)
        assertEquals("Demo App restored", restored.name)
        assertEquals(project.type, restored.type)
        assertEquals(project.packageName, restored.packageName)
        assertEquals("edited after backup", env.store.readFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt"))
        assertEquals("package com.demo.app\nclass MainActivity\n", env.store.readFile(restored.id, "app/src/main/java/com/demo/app/MainActivity.kt"))
        assertTrue(File(env.projectsDir, "${restored.id}/extra/app/src/main/res/mipmap-hdpi/ic_launcher.png").isFile)

        val again = env.backups.restoreAsNew(backup.id)
        assertEquals("Demo App restored 2", again.name)
        assertEquals(3, env.store.getProjects().size)
    }

    @Test
    fun restore_replacing_makes_a_safety_backup_first() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val backup = env.backups.createBackup(project.id, "good state")
        env.store.writeFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt", "bad edit")
        env.store.createFile(project.id, "added-later.txt", "x")

        env.backups.restoreReplacing(backup.id)

        assertEquals("package com.demo.app\nclass MainActivity\n", env.store.readFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt"))
        assertFalse(env.store.listFiles(project.id).any { it.path == "added-later.txt" })
        assertTrue(File(env.projectsDir, "${project.id}/extra/gradle/wrapper/gradle-wrapper.jar").isFile)
        assertFalse(File(env.projectsDir, "${project.id}/replaced-old").exists())

        val safety = env.backups.listBackups(project.id).first { it.kind == BackupKind.BEFORE_RESTORE }
        // restoring the safety copy brings the "bad edit" state back: nothing was lost
        env.backups.restoreReplacing(safety.id)
        assertEquals("bad edit", env.store.readFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt"))
        assertEquals(1, env.store.getProjects().size)
    }

    @Test
    fun backups_survive_project_deletion_and_can_restore_it() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val backup = env.backups.createBackup(project.id)
        env.store.deleteProject(project.id)

        assertEquals(1, env.backups.listBackups(null).size)
        try {
            env.backups.restoreReplacing(backup.id)
            fail("original is gone")
        } catch (e: TransferException) {
            assertEquals(Kind.NOT_FOUND, e.kind)
        }
        val restored = env.backups.restoreAsNew(backup.id)
        assertEquals("Demo App restored", restored.name)
        assertTrue(env.store.listFiles(restored.id).isNotEmpty())
    }

    @Test
    fun delete_backup_removes_its_files() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val a = env.backups.createBackup(project.id)
        val b = env.backups.createBackup(project.id)
        env.backups.deleteBackup(a.id)
        assertEquals(listOf(b.id), env.backups.listBackups(project.id).map { it.id })
        assertFalse(File(env.backupsDir, "${project.id}/${a.id}.zip").exists())
        env.backups.deleteBackup(b.id)
        assertTrue(env.backups.listBackups(null).isEmpty())
        try {
            env.backups.deleteBackup(b.id)
            fail("already deleted")
        } catch (e: TransferException) {
            assertEquals(Kind.NOT_FOUND, e.kind)
        }
    }

    @Test
    fun manual_backup_limit_is_enforced_without_deleting_anything() = runTest {
        val env = TransferEnv(tmp, maxBackups = 2)
        val project = importDemo(env)
        env.backups.createBackup(project.id)
        env.backups.createBackup(project.id)
        try {
            env.backups.createBackup(project.id)
            fail("limit")
        } catch (e: TransferException) {
            assertEquals(Kind.LIMIT_REACHED, e.kind)
        }
        assertEquals(2, env.backups.listBackups(project.id).size)
    }

    @Test
    fun incomplete_or_damaged_backups_are_ignored_not_fatal() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val good = env.backups.createBackup(project.id)
        val dir = File(env.backupsDir, project.id)
        File(dir, "orphan.zip").writeBytes(byteArrayOf(1, 2, 3))             // zip without metadata
        File(dir, "broken.properties").writeText("id=broken\n")                // metadata without zip
        File(dir, "stale.zip.part").writeBytes(byteArrayOf(1))
        assertEquals(listOf(good.id), env.backups.listBackups(project.id).map { it.id })
    }

    @Test
    fun a_corrupt_backup_zip_fails_restore_cleanly() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        val backup = env.backups.createBackup(project.id)
        File(env.backupsDir, "${project.id}/${backup.id}.zip").writeBytes(byteArrayOf(9, 9, 9, 9, 9, 9))
        try {
            env.backups.restoreAsNew(backup.id)
            fail("corrupt")
        } catch (e: TransferException) {
            assertEquals(Kind.NOT_A_ZIP, e.kind)
        }
        assertEquals(1, env.store.getProjects().size)
        assertTrue(env.stagingLeftovers().isEmpty())
    }

    @Test
    fun failed_replace_restores_the_previous_files() = runTest {
        val env = TransferEnv(tmp)
        val project = importDemo(env)
        // staged folder with nothing in it: replaceContents must refuse and change nothing
        val empty = File(tmp.root, "empty-stage").apply { mkdirs() }
        try {
            env.store.replaceContents(project.id, empty)
            fail("expected failure")
        } catch (e: Exception) {
            // expected
        }
        assertEquals("package com.demo.app\nclass MainActivity\n", env.store.readFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt"))
        assertFalse(File(env.projectsDir, "${project.id}/replaced-old").exists())
    }

    @Test
    fun backup_ids_from_outside_cannot_escape_the_backup_folder() = runTest {
        val env = TransferEnv(tmp)
        for (bad in listOf("../x", "a/b", "..", "")) {
            try {
                env.backups.deleteBackup(bad)
                fail("'$bad' must be rejected")
            } catch (e: TransferException) {
                assertEquals(Kind.NOT_FOUND, e.kind)
            }
        }
        assertTrue(env.backups.listBackups("../projects").isEmpty())
    }
}
