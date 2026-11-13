package com.almus.appcreatorpro.explorer

import com.almus.appcreatorpro.data.storage.FileProjectStore
import com.almus.appcreatorpro.domain.model.ProjectType
import com.almus.appcreatorpro.editor.EditorValue
import com.almus.appcreatorpro.testutil.MainDispatcherRule
import com.almus.appcreatorpro.ui.editor.EditorViewModel
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

class Phase25EditorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: FileProjectStore
    private lateinit var id: String
    private lateinit var recentStore: InMemoryRecentFilesStore

    @Before
    fun setUp() = runTest {
        store = FileProjectStore(File(tmp.root, "projects"), ioDispatcher = Dispatchers.Unconfined)
        id = store.createProject("Demo", "", "", ProjectType.WEB).id
        recentStore = InMemoryRecentFilesStore()
    }

    private fun vm() = EditorViewModel(id, store, store, recentStore)

    private fun EditorViewModel.type(text: String) {
        val open = state.value.activeFile!!
        onUserEdit(EditorValue(open.value.text + text, open.value.text.length + text.length))
    }

    private suspend fun paths() = store.listFiles(id).map { it.path }.toSet()

    // ---------------------------------------------------------------- recent files

    @Test
    fun opened_files_become_recent_newest_first() {
        val vm = vm()
        vm.openFile("script.js")
        vm.openFile("style.css")
        vm.openFile("script.js")
        assertEquals(listOf("script.js", "style.css", "index.html"), vm.state.value.recentFiles)
    }

    @Test
    fun recent_files_survive_a_restart_and_drop_files_that_no_longer_exist() = runTest {
        val first = vm()
        first.openFile("style.css")
        recentStore.save(id, RecentFiles(listOf("ghost.kt") + recentStore.load(id).paths))
        val second = vm()
        assertTrue("style.css" in second.state.value.recentFiles)
        assertFalse("ghost.kt" in second.state.value.recentFiles)
    }

    @Test
    fun rename_and_delete_keep_the_recent_list_in_step() = runTest {
        val vm = vm()
        vm.openFile("script.js")
        vm.renameFile("script.js", "main.js")
        assertTrue("main.js" in vm.state.value.recentFiles)
        assertFalse("script.js" in vm.state.value.recentFiles)
        vm.openFile("style.css")
        vm.requestDelete("style.css", isFolder = false)
        vm.confirmDelete()
        assertFalse("style.css" in vm.state.value.recentFiles)
        assertFalse("style.css" in recentStore.load(id).paths)
    }

    @Test
    fun clearing_recent_empties_the_list() {
        val vm = vm()
        vm.openFile("script.js")
        vm.clearRecent()
        assertTrue(vm.state.value.recentFiles.isEmpty())
        assertTrue(recentStore.load(id).paths.isEmpty())
    }

    // ---------------------------------------------------------------- search and sort state

    @Test
    fun search_and_sort_are_kept_in_the_state() {
        val vm = vm()
        vm.setSearchQuery("style")
        vm.setSort(ExplorerSort.SIZE)
        assertEquals("style", vm.state.value.searchQuery)
        assertEquals(ExplorerSort.SIZE, vm.state.value.sort)
        val rows = ExplorerTree.rows(vm.state.value.files, emptySet(), vm.state.value.searchQuery, vm.state.value.sort)
        assertEquals(listOf("style.css"), rows.map { it.path })
    }

    // ---------------------------------------------------------------- create / duplicate

    @Test
    fun duplicating_creates_a_copy_and_opens_it() = runTest {
        val vm = vm()
        vm.duplicateFile("style.css")
        assertTrue("style copy.css" in paths())
        assertEquals("style copy.css", vm.state.value.activePath)
        assertTrue(vm.state.value.files.any { it.path == "style copy.css" })
        assertEquals(store.readFile(id, "style.css"), store.readFile(id, "style copy.css"))
        assertFalse(vm.message.value!!.isError)
    }

    @Test
    fun creating_a_folder_adds_a_gitkeep() = runTest {
        val vm = vm()
        vm.createFolder("src/utils")
        assertTrue("src/utils/.gitkeep" in paths())
        assertTrue(vm.state.value.files.any { it.path == "src/utils/.gitkeep" })
        vm.createFolder("src/utils")
        assertTrue(vm.message.value!!.isError)
    }

    // ---------------------------------------------------------------- delete with confirmation

    @Test
    fun a_delete_request_changes_nothing_until_confirmed() = runTest {
        val vm = vm()
        vm.requestDelete("script.js", isFolder = false)
        val plan = vm.state.value.pendingDelete
        assertNotNull(plan)
        assertEquals(listOf("script.js"), plan!!.files)
        assertTrue("script.js" in paths())

        vm.cancelDelete()
        assertNull(vm.state.value.pendingDelete)
        assertTrue("script.js" in paths())

        vm.requestDelete("script.js", isFolder = false)
        vm.confirmDelete()
        assertNull(vm.state.value.pendingDelete)
        assertFalse("script.js" in paths())
        assertFalse(vm.state.value.files.any { it.path == "script.js" })
    }

    @Test
    fun the_delete_plan_lists_unsaved_open_files() {
        val vm = vm()
        vm.type("<!-- unsaved -->")
        vm.requestDelete("index.html", isFolder = false)
        assertEquals(listOf("index.html"), vm.state.value.pendingDelete!!.unsaved)
    }

    @Test
    fun deleting_a_folder_closes_the_tabs_inside_it() = runTest {
        store.createFile(id, "src/A.kt", "A")
        store.createFile(id, "src/B.kt", "B")
        val vm = vm()
        vm.load()
        vm.openFile("src/A.kt")
        vm.requestDelete("src", isFolder = true)
        assertEquals(2, vm.state.value.pendingDelete!!.files.size)
        vm.confirmDelete()
        assertTrue(vm.state.value.openFiles.none { it.path.startsWith("src/") })
        assertTrue(paths().none { it.startsWith("src/") })
    }

    @Test
    fun important_files_are_protected_from_the_plain_delete() = runTest {
        store.createFile(id, "build.gradle.kts", "plugins {}")
        val vm = vm()
        vm.load()
        vm.deleteFile("build.gradle.kts")
        assertTrue(vm.message.value!!.isError)
        assertTrue("build.gradle.kts" in paths())
    }

    @Test
    fun important_files_need_the_name_typed_through_the_explorer() = runTest {
        store.createFile(id, "build.gradle.kts", "plugins {}")
        val vm = vm()
        vm.load()
        vm.requestDelete("build.gradle.kts", isFolder = false)
        assertTrue(vm.state.value.pendingDelete!!.needsTypedConfirmation)

        vm.confirmDelete(null)
        vm.confirmDelete("build.gradle")
        assertNotNull(vm.state.value.pendingDelete) // still waiting
        assertTrue("build.gradle.kts" in paths())

        vm.confirmDelete("build.gradle.kts")
        assertNull(vm.state.value.pendingDelete)
        assertFalse("build.gradle.kts" in paths())
    }

    // ---------------------------------------------------------------- rename with protection

    @Test
    fun renaming_an_important_file_waits_for_confirmation() = runTest {
        store.createFile(id, "settings.gradle.kts", "x")
        val vm = vm()
        vm.load()
        vm.renameFile("settings.gradle.kts", "settings-old.gradle.kts")
        val pending = vm.state.value.pendingProtectedRename
        assertNotNull(pending)
        assertTrue("settings.gradle.kts" in paths())

        vm.cancelProtectedRename()
        assertNull(vm.state.value.pendingProtectedRename)
        assertTrue("settings.gradle.kts" in paths())

        vm.renameFile("settings.gradle.kts", "settings-old.gradle.kts")
        vm.confirmProtectedRename()
        assertNull(vm.state.value.pendingProtectedRename)
        assertTrue("settings-old.gradle.kts" in paths())
        assertFalse("settings.gradle.kts" in paths())
    }

    @Test
    fun renaming_a_folder_moves_open_tabs_with_their_unsaved_text() = runTest {
        store.createFile(id, "src/A.kt", "A")
        val vm = vm()
        vm.load()
        vm.openFile("src/A.kt")
        vm.type("// edit")
        vm.renameFolder("src", "lib")
        val open = vm.state.value.activeFile!!
        assertEquals("lib/A.kt", open.path)
        assertTrue(open.isDirty)
        assertTrue(open.value.text.endsWith("// edit"))
        assertTrue("lib/A.kt" in vm.state.value.recentFiles)
        assertTrue(vm.state.value.files.none { it.path.startsWith("src/") })
    }

    // ---------------------------------------------------------------- preview

    @Test
    fun preview_shows_the_file_without_opening_it() = runTest {
        val vm = vm()
        vm.previewFile("style.css")
        val ui = vm.state.value.preview!!
        assertFalse(ui.loading)
        assertNull(ui.error)
        assertEquals(store.readFile(id, "style.css"), ui.preview!!.text)
        assertEquals(FileKind.CSS, ui.preview!!.kind)
        assertFalse(vm.state.value.openFiles.any { it.path == "style.css" })
        vm.closePreview()
        assertNull(vm.state.value.preview)
    }

    @Test
    fun preview_of_an_open_file_shows_its_unsaved_text() {
        val vm = vm()
        vm.type("<!-- fresh -->")
        vm.previewFile("index.html")
        assertTrue(vm.state.value.preview!!.preview!!.text.endsWith("<!-- fresh -->"))
    }

    @Test
    fun preview_of_unreadable_or_binary_files_explains_why() = runTest {
        File(tmp.root, "projects/$id/files/pic.png").writeBytes(byteArrayOf(1, 2, 3))
        File(tmp.root, "projects/$id/files/odd.dat").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))
        val vm = vm()
        vm.load()
        vm.previewFile("pic.png")
        assertFalse(vm.state.value.preview!!.preview!!.hasText)
        vm.previewFile("odd.dat")
        assertNotNull(vm.state.value.preview!!.error)
        vm.previewFile("ghost.txt")
        assertNotNull(vm.state.value.preview!!.error)
    }

    // ---------------------------------------------------------------- existing behaviour is unchanged

    @Test
    fun the_old_file_operations_still_behave_as_before() = runTest {
        val vm = vm()
        vm.createFile(" js/app.js ")
        assertEquals("Created js/app.js", vm.message.value?.text)
        assertEquals("js/app.js", vm.state.value.activePath)
        vm.renameFile("script.js", "main.js")
        assertEquals("Renamed to main.js", vm.message.value?.text)
        vm.deleteFile("style.css")
        assertFalse("style.css" in paths())
        vm.createFile("index.html")
        assertTrue(vm.message.value!!.isError)
    }
}
