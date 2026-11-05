package com.almus.appcreatorpro.transfer

import com.almus.appcreatorpro.domain.error.ValidationException
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.domain.transfer.ImportLimits
import com.almus.appcreatorpro.domain.transfer.ImportSourceKind
import com.almus.appcreatorpro.domain.transfer.TransferException
import com.almus.appcreatorpro.domain.transfer.TransferException.Kind
import com.almus.appcreatorpro.domain.transfer.TransferProgress
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ProjectImportServiceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private suspend fun stage(env: TransferEnv, bytes: ByteArray, name: String = "Demo.zip", strip: Boolean = false) =
        env.importer.stageZip(ByteArrayInputStream(bytes), bytes.size.toLong(), name, ImportSourceKind.ZIP_FILE, strip)

    private suspend fun expectFailure(env: TransferEnv, bytes: ByteArray, kind: Kind, strip: Boolean = false) {
        try {
            stage(env, bytes, strip = strip)
            fail("Expected $kind")
        } catch (e: TransferException) {
            assertEquals(kind, e.kind)
        }
        assertTrue("staging must be cleaned up", env.stagingLeftovers().isEmpty())
        assertTrue("no project may be created", env.store.getProjects().isEmpty())
    }

    // ---------------------------------------------------------------- detection and preview

    @Test
    fun android_kotlin_zip_is_detected_and_files_are_classified() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(env, zipBytes(*androidKotlinFiles()))
        val p = staged.preview
        assertEquals(ProjectType.ANDROID_KOTLIN, p.type)
        assertEquals("com.demo.app", p.packageName)
        assertTrue(p.canImport)
        assertEquals("Demo App", p.suggestedName) // from rootProject.name
        // png and jar are kept but not editable
        assertEquals(2, p.preservedFiles)
        assertEquals(7, p.editableFiles)
        assertTrue(File(staged.dir, "extra/app/src/main/res/mipmap-hdpi/ic_launcher.png").isFile)
        assertTrue(File(staged.dir, "files/app/src/main/java/com/demo/app/MainActivity.kt").isFile)
        assertTrue(p.warnings.any { it.contains("binary") })
    }

    @Test
    fun wrapper_folder_is_removed_when_it_holds_the_project_root() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(env, zipBytes(*androidKotlinFiles(wrapper = "MyApp-main")))
        assertTrue(File(staged.dir, "files/settings.gradle.kts").isFile)
        assertFalse(File(staged.dir, "files/MyApp-main").exists())
    }

    @Test
    fun a_plain_src_folder_is_not_mistaken_for_a_wrapper() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(env, zipBytes("src/index.html" to text("<html/>"), "src/app.js" to text("1")))
        assertTrue(File(staged.dir, "files/src/index.html").isFile)
        assertEquals(ProjectType.WEB, staged.preview.type)
    }

    @Test
    fun github_style_archives_always_lose_their_single_root_folder() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(env, zipBytes("owner-repo-abc123/index.html" to text("<html/>"), "owner-repo-abc123/css/a.css" to text("a{}")), strip = true)
        assertTrue(File(staged.dir, "files/index.html").isFile)
        assertTrue(File(staged.dir, "files/css/a.css").isFile)
    }

    @Test
    fun java_project_without_kotlin_is_android_java() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(
            env,
            zipBytes(
                "build.gradle" to text("plugins { id 'com.android.application' }\nandroid { namespace 'org.sample.app' }\n"),
                "src/main/AndroidManifest.xml" to text("<manifest/>"),
                "src/main/java/org/sample/app/Main.java" to text("class Main {}"),
            ),
        )
        assertEquals(ProjectType.ANDROID_JAVA, staged.preview.type)
        assertEquals("org.sample.app", staged.preview.packageName)
    }

    @Test
    fun flutter_and_unknown_projects_are_reported_not_imported() = runTest {
        val env = TransferEnv(tmp)
        val flutter = stage(env, zipBytes("pubspec.yaml" to text("name: x"), "lib/main.dart" to text("void main(){}")))
        assertFalse(flutter.preview.canImport)
        assertTrue(flutter.preview.problems.first().contains("Flutter"))
        try {
            env.importer.commit(flutter, "Some Name")
            fail("commit must refuse an unsupported project")
        } catch (e: TransferException) {
            assertEquals(Kind.UNSUPPORTED_PROJECT, e.kind)
        }
        val unknown = stage(env, zipBytes("notes.txt" to text("hi"), "data.csv" to text("a,b")))
        assertFalse(unknown.preview.canImport)
    }

    @Test
    fun ignored_files_are_counted_and_reported() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(
            env,
            zipBytes(
                *androidKotlinFiles(),
                ".git/HEAD" to text("ref"),
                "local.properties" to text("sdk.dir=/x"),
                "app/build/intermediates/a.class" to text("x"),
                "release.jks" to byteArrayOf(1, 2, 3),
            ),
        )
        assertFalse(File(staged.dir, "files/local.properties").exists())
        assertFalse(File(staged.dir, "extra/release.jks").exists())
        assertTrue(staged.preview.warnings.any { it.contains("left out") })
    }

    // ---------------------------------------------------------------- committing

    @Test
    fun commit_creates_a_project_whose_files_the_editor_can_read() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(env, zipBytes(*androidKotlinFiles()))
        val project = env.importer.commit(staged, "Imported Demo")
        assertEquals("Imported Demo", project.name)
        assertEquals(ProjectType.ANDROID_KOTLIN, project.type)
        assertEquals("com.demo.app", project.packageName)
        assertEquals(listOf(project), env.store.getProjects())
        val editable = env.store.listFiles(project.id).map { it.path }
        assertTrue(editable.contains("app/src/main/java/com/demo/app/MainActivity.kt"))
        // preserved binary files are NOT listed (the editor and cloud builds only see text files) ...
        assertFalse(editable.any { it.endsWith(".png") || it.endsWith(".jar") })
        // ... but they are on disk
        assertTrue(File(env.projectsDir, "${project.id}/extra/app/src/main/res/mipmap-hdpi/ic_launcher.png").isFile)
        assertEquals("package com.demo.app\nclass MainActivity\n", env.store.readFile(project.id, "app/src/main/java/com/demo/app/MainActivity.kt"))
        assertTrue("staging is removed after commit", env.stagingLeftovers().isEmpty())
    }

    @Test
    fun duplicate_name_is_rejected_but_staged_files_are_kept_for_a_retry() = runTest {
        val env = TransferEnv(tmp)
        env.store.createProject("Taken Name", "com.example.taken", "")
        val staged = stage(env, zipBytes(*androidKotlinFiles()))
        try {
            env.importer.commit(staged, "taken name")
            fail("expected ValidationException")
        } catch (e: ValidationException) {
            assertNotNull(e.message)
        }
        assertTrue(staged.dir.isDirectory)
        val project = env.importer.commit(staged, "Another Name")
        assertEquals("Another Name", project.name)
        assertEquals(2, env.store.getProjects().size)
    }

    @Test
    fun suggested_name_is_made_unique() = runTest {
        val env = TransferEnv(tmp)
        env.store.createProject("Demo App", "com.example.demoapp", "")
        val staged = stage(env, zipBytes(*androidKotlinFiles()))
        assertEquals("Demo App 2", staged.preview.suggestedName)
    }

    @Test
    fun discard_removes_the_staging_folder() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(env, zipBytes(*androidKotlinFiles()))
        env.importer.discard(staged)
        assertFalse(staged.dir.exists())
    }

    @Test
    fun stale_staging_is_cleaned_at_startup() {
        val env = TransferEnv(tmp)
        File(env.stagingDir, "import-old/raw").mkdirs()
        File(env.stagingDir, "import-old/raw/x.txt").writeText("x")
        env.importer.cleanStaleStaging()
        assertTrue(env.stagingLeftovers().isEmpty())
    }

    // ---------------------------------------------------------------- failures leave nothing behind

    @Test
    fun not_a_zip_is_rejected() = runTest {
        val env = TransferEnv(tmp)
        expectFailure(env, text("this is just text, not a zip"), Kind.NOT_A_ZIP)
        expectFailure(env, ByteArray(0), Kind.NOT_A_ZIP)
    }

    @Test
    fun truncated_zip_is_rejected_as_corrupt_or_empty() = runTest {
        val env = TransferEnv(tmp)
        val good = zipBytes(*androidKotlinFiles())
        val truncated = good.copyOf(good.size / 2)
        try {
            stage(env, truncated)
            // A cut-off archive may still yield some complete entries; it must then never create a project by itself.
            assertTrue(env.store.getProjects().isEmpty())
        } catch (e: TransferException) {
            assertTrue(e.kind == Kind.CORRUPT_ARCHIVE || e.kind == Kind.READ_FAILED || e.kind == Kind.EMPTY)
        }
    }

    @Test
    fun zip_slip_archives_are_rejected_completely() = runTest {
        val env = TransferEnv(tmp)
        expectFailure(env, zipBytes("ok.html" to text("<html/>"), "../../evil.txt" to text("x")), Kind.UNSAFE_PATH)
        expectFailure(env, zipBytes("a/../../../evil.txt" to text("x")), Kind.UNSAFE_PATH)
        expectFailure(env, zipBytes("/abs/evil.txt" to text("x")), Kind.UNSAFE_PATH)
        expectFailure(env, zipBytes("C:\\evil.txt" to text("x")), Kind.UNSAFE_PATH)
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
    }

    @Test
    fun too_many_entries_is_rejected() = runTest {
        val env = TransferEnv(tmp, ImportLimits(maxEntries = 5))
        val entries = (1..6).map { "f$it.html" to text("<html/>") }.toTypedArray()
        expectFailure(env, zipBytes(*entries), Kind.TOO_MANY_FILES)
    }

    @Test
    fun total_size_limit_counts_real_bytes() = runTest {
        val env = TransferEnv(tmp, ImportLimits(maxTotalBytes = 10_000, maxFileBytes = 6_000))
        val chunk = ByteArray(5_000) { 'a'.code.toByte() }
        expectFailure(env, zipBytes("a.txt" to chunk, "b.txt" to chunk, "c.txt" to chunk), Kind.TOO_LARGE)
    }

    @Test
    fun zip_bomb_ratio_is_rejected() = runTest {
        val env = TransferEnv(tmp, ImportLimits(maxTotalBytes = 50_000_000, maxFileBytes = 50_000_000, ratioCheckAfterBytes = 1_000_000, maxCompressionRatio = 100))
        val zeros = ByteArray(10_000_000) // compresses ~1000:1
        expectFailure(env, zipBytes("bomb.txt" to zeros), Kind.TOO_LARGE)
    }

    @Test
    fun an_oversized_single_file_is_skipped_not_fatal() = runTest {
        val env = TransferEnv(tmp, ImportLimits(maxFileBytes = 1_000))
        val staged = stage(env, zipBytes("index.html" to text("<html/>"), "big.bin" to ByteArray(5_000) { 7 }))
        assertEquals(1, staged.preview.editableFiles)
        assertEquals(0, staged.preview.preservedFiles)
        assertTrue(staged.preview.warnings.any { it.contains("size limit") })
    }

    @Test
    fun an_archive_with_only_ignored_files_is_empty() = runTest {
        val env = TransferEnv(tmp)
        expectFailure(env, zipBytes(".git/HEAD" to text("x"), "local.properties" to text("x")), Kind.EMPTY)
    }

    @Test
    fun duplicate_entries_keep_the_first_one() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(env, zipBytes("index.html" to text("first"), "./index.html" to text("second")))
        assertEquals("first", File(staged.dir, "files/index.html").readText())
    }

    @Test
    fun cancellation_cleans_up_staging() = runTest {
        val env = TransferEnv(tmp)
        val bytes = zipBytes(*androidKotlinFiles())
        var cancelled = false
        try {
            env.importer.stageZip(ByteArrayInputStream(bytes), null, "x.zip", ImportSourceKind.ZIP_FILE) { _ ->
                cancelled = true
                throw kotlinx.coroutines.CancellationException("user cancelled")
            }
            fail("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertTrue(cancelled)
        }
        assertTrue(env.stagingLeftovers().isEmpty())
        assertTrue(env.store.getProjects().isEmpty())
    }

    // ---------------------------------------------------------------- folder (Storage Access Framework) import

    @Test
    fun folder_import_keeps_structure_and_uses_the_folder_name() = runTest {
        val env = TransferEnv(tmp)
        val files = androidKotlinFiles().associate { it.first to it.second }
        val staged = env.importer.stageTree(FakeTree("My Studio Project", files))
        assertEquals("My Studio Project", staged.preview.suggestedName)
        assertEquals(ProjectType.ANDROID_KOTLIN, staged.preview.type)
        assertEquals(ImportSourceKind.LOCAL_FOLDER, staged.preview.sourceKind)
        val project = env.importer.commit(staged, staged.preview.suggestedName)
        assertTrue(env.store.listFiles(project.id).any { it.path == "app/build.gradle.kts" })
    }

    @Test
    fun folder_import_skips_unsafe_names_by_rejecting() = runTest {
        val env = TransferEnv(tmp)
        try {
            env.importer.stageTree(FakeTree("x", mapOf("../oops.txt" to text("x"))))
            fail("expected UNSAFE_PATH")
        } catch (e: TransferException) {
            assertEquals(Kind.UNSAFE_PATH, e.kind)
        }
        assertTrue(env.stagingLeftovers().isEmpty())
    }

    @Test
    fun progress_is_reported_while_reading() = runTest {
        val env = TransferEnv(tmp)
        val seen = ArrayList<TransferProgress>()
        val bytes = zipBytes(*androidKotlinFiles())
        env.importer.stageZip(ByteArrayInputStream(bytes), bytes.size.toLong(), "d.zip", ImportSourceKind.ZIP_FILE) { seen += it }
        assertTrue(seen.any { it.phase == TransferProgress.Phase.READING && it.files > 0 })
        assertTrue(seen.any { it.phase == TransferProgress.Phase.CHECKING })
    }

    @Test
    fun editable_and_preserved_classification_edge_cases() = runTest {
        val env = TransferEnv(tmp)
        val staged = stage(
            env,
            zipBytes(
                "index.html" to text("<html/>"),
                "weird name (1).txt" to text("parens are not allowed by the editor"),
                "latin1.txt" to byteArrayOf(0x63, 0xE9.toByte(), 0x66, 0xE9.toByte()),
                "ok.txt" to text("ünïcödé is fine"),
            ),
        )
        assertTrue(File(staged.dir, "files/index.html").isFile)
        assertTrue(File(staged.dir, "files/ok.txt").isFile)
        assertTrue(File(staged.dir, "extra/weird name (1).txt").isFile)
        assertTrue(File(staged.dir, "extra/latin1.txt").isFile)
    }
}
